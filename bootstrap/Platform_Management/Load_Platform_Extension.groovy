// Constants
def platformManagementFolderName= "/Platform_Management"
def platformManagementFolder = folder(platformManagementFolderName) { displayName('Platform Management') }

// Jobs
def loadPlatformExtensionJob = freeStyleJob(platformManagementFolderName + "/Load_Platform_Extension")

// Setup setup_cartridge
loadPlatformExtensionJob.with{
    description("This job loads platform extensions. It currently supports the following platform extension types: aws, docker.")
    wrappers {
        preBuildCleanup()
        sshAgent('adop-jenkins-master')
    }
    parameters{
        stringParam("GIT_URL",'',"The URL of the git repo for Platform Extension")
        stringParam("GIT_REF","master","The reference to checkout from git repo of Platform Extension. It could be a branch name or a tag name. Eg : master, 0.0.1 etc")
        credentialsParam("CREDENTIALS"){
            type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
            defaultValue('adop-default')
            description('Platform extension credentials. Note: Leave at adop-default if credentials are not required.')
        }
    }
    scm{
      git{
        remote{
          url('${GIT_URL}')
          credentials("adop-jenkins-master")
        }
        branch('${GIT_REF}')
      }
    }
    label("aws")
    wrappers {
      preBuildCleanup()
      injectPasswords()
      maskPasswords()
      sshAgent("adop-jenkins-master")
      credentialsBinding {
        usernamePassword("USERNAME", "PASSWORD", '${CREDENTIALS}')
      }
    }
    steps {
        shell('''#!/bin/bash -ex

echo "This job loads the platform extension ${GIT_URL}"

# Source metadata
if [ -f ${WORKSPACE}/extension.metadata ]; then
    source ${WORKSPACE}/extension.metadata
fi

if [ ! -d service/ ] && [ ! -d /service_ext ] ; then
  echo "ERROR:  Platform extension service or service extensions not found."
  echo "INFO :  Please see https://github.com/Accenture/adop-platform-extension-specification for the platform extension specification."
  exit 1
fi

if [ -d service/ ] ; then

  # Find platform extension type.
  if [ -z ${PLATFORM_EXTENSION_TYPE} ] ; then
    PLATFORM_EXTENSION_TYPE=$(find service/ -mindepth 1 -maxdepth 1 -type d | head -1 | cut -d/ -f2)
  fi

  if [ ! -d ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE} ] ; then
      echo "ERROR : /service/${PLATFORM_EXTENSION_TYPE} extension directory not found."
      exit 1
  fi

  # Infer platform load strategy based on platform extension type.
  case "${PLATFORM_EXTENSION_TYPE}" in
      aws)
          # Provision any EC2 instances in the AWS folder
          if [ -f ${WORKSPACE}/service/aws/service.template ]; then

              echo "#######################################"
              echo "Adding EC2 platform extension on AWS..."

              export AWS_ACCESS_KEY_ID=${USERNAME}
              export AWS_SECRET_ACCESS_KEY=${PASSWORD}

              # Variables
              INSTANCE_ID=$(curl http://169.254.169.254/latest/meta-data/instance-id)
              PUBLIC_IP=$(curl http://169.254.169.254/latest/meta-data/public-ipv4)
              if [ "$AWS_SUBNET_ID" = "default" ]; then
                  echo "Subnet not set, using default public subnet where ADOP is deployed..."
                  AWS_SUBNET_ID=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID}           --query 'Reservations[0].Instances[0].SubnetId' --output text);
              fi
              if [ -z $AWS_VPC_ID ]; then
                  echo "VPC ID not set, using default VPC where ADOP is deployed..."
                  AWS_VPC_ID=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID}           --query 'Reservations[0].Instances[0].VpcId' --output text);
              fi
              CIDR_BLOCK=$(aws ec2 describe-vpcs --vpc-ids ${AWS_VPC_ID} --query 'Vpcs[0].CidrBlock' --output text)

              ENVIRONMENT_STACK_NAME="${AWS_VPC_ID}-EC2-PLATFORM-EXTENSION-${PLATFORM_EXTENSION_NAME}-${BUILD_NUMBER}"
              FULL_ENVIRONMENT_NAME="${AWS_VPC_ID}-EC2-Instance-${PLATFORM_EXTENSION_NAME}-${BUILD_NUMBER}"

              aws cloudformation create-stack --stack-name ${ENVIRONMENT_STACK_NAME} \
              --tags "Key=createdBy,Value=ADOP-Jenkins" "Key=user,Value=${INITIAL_ADMIN_USER}" \
              --template-body file://service/aws/service.template \
              --parameters ParameterKey=EnvironmentName,ParameterValue=${FULL_ENVIRONMENT_NAME} \
              ParameterKey=InstanceType,ParameterValue=${AWS_INSTANCE_TYPE} \
              ParameterKey=EnvironmentSubnet,ParameterValue=${AWS_SUBNET_ID} \
              ParameterKey=KeyName,ParameterValue=${AWS_KEYPAIR} \
              ParameterKey=VPCId,ParameterValue=${AWS_VPC_ID} \
              ParameterKey=InboundCIDR,ParameterValue=${CIDR_BLOCK}

              # Keep looping whilst the stack is being created
              SLEEP_TIME=60
              COUNT=0
              TIME_SPENT=0
              while aws cloudformation describe-stacks --stack-name ${ENVIRONMENT_STACK_NAME} | grep -q "CREATE_IN_PROGRESS" > /dev/null
              do
                  TIME_SPENT=$(($COUNT * $SLEEP_TIME))
                  echo "Attempt ${COUNT} : Stack creation in progress (Time spent : ${TIME_SPENT} seconds)"
                  sleep "${SLEEP_TIME}"
                  COUNT=$((COUNT+1))
              done

              # Check that the stack created
              TIME_SPENT=$(($COUNT * $SLEEP_TIME))
              if $(aws cloudformation describe-stacks --stack-name ${ENVIRONMENT_STACK_NAME} | grep -q "CREATE_COMPLETE")
              then
                  echo "Stack has been created in approximately ${TIME_SPENT} seconds."
                  NODE_IP=$(aws cloudformation describe-stacks --stack-name ${ENVIRONMENT_STACK_NAME} --query 'Stacks[].Outputs[?OutputKey==`EC2InstancePrivateIp`].OutputValue' --output text)
                  NEW_INSTANCE_ID=$(aws cloudformation describe-stacks --stack-name ${ENVIRONMENT_STACK_NAME} --query 'Stacks[].Outputs[?OutputKey==`EC2InstanceID`].OutputValue' --output text)
              else
                  echo "ERROR : Stack creation failed after ${TIME_SPENT} seconds. Please check the AWS console for more information."
                  exit 1
              fi

              echo "Success! The private IP of your new EC2 instance is $NODE_IP"
              echo "Please use your provided key, ${AWS_KEYPAIR}, in order to SSH onto the instance."

              # Keep looping whilst the EC2 instance is still initializing
              COUNT=0
              TIME_SPENT=0
              while aws ec2 describe-instance-status --instance-ids ${NEW_INSTANCE_ID} | grep -q "initializing" > /dev/null
              do
                  TIME_SPENT=$(($COUNT * $SLEEP_TIME))
                  echo "Attempt ${COUNT} : EC2 Instance still initializing (Time spent : ${TIME_SPENT} seconds)"
                  sleep "${SLEEP_TIME}"
                  COUNT=$((COUNT+1))
              done

              # Check that the instance has initalized and all tests have passed
              TIME_SPENT=$(($COUNT * $SLEEP_TIME))
              if $(aws ec2 describe-instance-status --instance-ids ${NEW_INSTANCE_ID} --query 'InstanceStatuses[0].InstanceStatus' --output text | grep -q "passed")
              then
                  echo "Instance has been initialized in approximately ${TIME_SPENT} seconds."
                  echo "Please change your default security group depending on the level of access you wish to enable."
              else
                  echo "ERROR : Instance initialization failed after ${TIME_SPENT} seconds. Please check the AWS console for more information."
                  exit 1
              fi

              if [ -f ${WORKSPACE}/service/aws/ec2-extension.conf ]; then

                  echo "#######################################"
                  echo "Adding EC2 instance to NGINX config using xip.io..."

                  export SERVICE_NAME="EC2-Service-Extension-${PLATFORM_EXTENSION_NAME}-${BUILD_NUMBER}"

                  ## Add nginx configuration
                  sed -i "s/###EC2_SERVICE_NAME###/${SERVICE_NAME}/" ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/ec2-extension.conf
                  sed -i "s/###EC2_HOST_IP###/${NODE_IP}/" ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/ec2-extension.conf

                  echo "You can check that your EC2 instance has been succesfully proxied by accessing the following URL: ${SERVICE_NAME}.${PUBLIC_IP}.xip.io"
              else
                  echo "INFO: /service/aws/ec2-extension.conf not found"
              fi

          else
              echo "ERROR: /service/aws/service.template not found"
              exit 1
          fi
         ;;
      docker)

        if [ -f ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/docker-compose.yml ] ; then
            SERVICE_NAME="Docker-Service-Extension-${PLATFORM_EXTENSION_NAME}-${BUILD_NUMBER}"

            if [ ! "${CREDENTIALS}" = "adop-default" ]; then
                DOCKER_PRIVATE_REPO=$(cat ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/docker-compose.yml | grep image | awk '{print $2}' | cut -d/ -f1 | head -1)
                docker login -u ${USERNAME} -p ${PASSWORD} -e example@example.com ${DOCKER_PRIVATE_REPO}
            fi

            docker-compose -f ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/docker-compose.yml -p ${SERVICE_NAME} up -d
        else
            echo "ERROR : /service/docker/docker-compose.yml not found."
            exit 1
        fi
      ;;
      *) echo "ERROR : Platform extension type not supported."
         exit 1
         ;;
  esac
fi

echo "INFO : Deploying proxy configuration."

RELOAD_PROXY=false
COUNT=0
for file in $(ls ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/*.{conf,ext} 2> /dev/null)
  do

    [[ -e ${file} ]] || break

    FILE_NAME=$(basename ${file})
    FILE_EXTENSION=${FILE_NAME##*.}

    if [ "${FILE_EXTENSION}" = "ext" ]; then
      DEPLOY_DIR=/etc/nginx/sites-enabled/service-extension/
    else
      DEPLOY_DIR=/etc/nginx/sites-enabled/
    fi

    docker cp ${file} proxy:${DEPLOY_DIR}${COUNT}-${SERVICE_NAME}.conf

    COUNT=$((COUNT+1))
    RELOAD_PROXY=true
done

if [ "${RELOAD_PROXY}" = true ] ; then
    docker exec proxy /usr/sbin/nginx -s reload
fi

echo "INFO : Platform extension ${PLATFORM_EXTENSION_NAME} loaded."
echo "INFO : Service extension unique ID: ${SERVICE_NAME}"
''')
    }
}
