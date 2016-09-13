
// Constants
def platformManagementFolderName= "/Platform_Management"
def platformManagementFolder = folder(platformManagementFolderName) { displayName('Platform Management') }
def platformExtensionTypes = ["Platform Type Not Selected", "aws", "docker"]

// Jobs
def loadPlatformExtensionJob = freeStyleJob(platformManagementFolderName + "/Load_Platform_Extension")

// Setup Load_Platform_Extension job.
loadPlatformExtensionJob.with{
    description("This Jenkins job is responsible for loading platform extensions. It currently supports the following platform extension types; aws, docker. Only complete the build parameters required by your extension type (e.g. AWS_* is for the AWS extension type only). Note: if using a AWS extension type please ensure the access/secret keys have the most restrictive IAM policy suitable for the extension assigned.")
    wrappers{
        preBuildCleanup()
        sshAgent('adop-jenkins-master')
    }
    parameters{
      choiceParam("PLATFORM_EXTENSION_TYPE",platformExtensionTypes,"The platform extension type.")
      stringParam("GIT_URL",'',"The platform extension Git repository URL. If this is a private repository please ensure the adop-jenkins-master SSH public key is granted access.")
      stringParam("GIT_REF","master","The Git platform extension repository reference (branch/tag name e.g. master, 0.0.1).")
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
    label("swarm")
    wrappers {
      preBuildCleanup()
      injectPasswords()
      maskPasswords()
      sshAgent("adop-jenkins-master")
    }
    steps {
        shell('''
#!/bin/bash +ex

echo "This job loads the platform extension ${GIT_URL}"

# Source metadata
if [ -f "${WORKSPACE}/extension.metadata" ]; then
    source "${WORKSPACE}/extension.metadata"
fi

echo
echo "#######################################"
echo "INFO: Adding ${PLATFORM_EXTENSION_TYPE} platform extension..."
echo

case "${PLATFORM_EXTENSION_TYPE}" in
    aws)
        # Provision any EC2 instances in the AWS folder
        if [ -d "${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}" ]; then

            if [ -f "${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/service.template" ]; then

                INSTANCE_ID=$(curl http://169.254.169.254/latest/meta-data/instance-id)
                PUBLIC_IP=$(curl http://169.254.169.254/latest/meta-data/public-ipv4)

                if [ "$AWS_SUBNET_ID" = "default" ]; then
                    echo "Subnet not set, using default public subnet where ADOP is deployed..."
                    AWS_SUBNET_ID=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID} --query 'Reservations[0].Instances[0].SubnetId' --output text);
                fi

                if [ -z $AWS_VPC_ID ]; then
                    echo "VPC ID not set, using default VPC where ADOP is deployed..."
                    AWS_VPC_ID=$(aws ec2 describe-instances --instance-ids ${INSTANCE_ID} --query 'Reservations[0].Instances[0].VpcId' --output text);
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

                echo "INFO: Success! The private IP of your new EC2 instance is $NODE_IP"
                echo "INFO: Please use your provided key, ${AWS_KEYPAIR}, in order to SSH onto the instance."

                # Keep looping whilst the EC2 instance is still initializing
                COUNT=0
                TIME_SPENT=0

                while aws ec2 describe-instance-status --instance-ids ${NEW_INSTANCE_ID} | grep -q "initializing" > /dev/null
                do
                    TIME_SPENT=$(($COUNT * $SLEEP_TIME))
                    echo "INFO: Attempt ${COUNT} : EC2 Instance still initializing (Time spent : ${TIME_SPENT} seconds)"
                    sleep "${SLEEP_TIME}"
                    COUNT=$((COUNT+1))
                done

                # Check that the instance has initalized and all tests have passed
                TIME_SPENT=$(($COUNT * $SLEEP_TIME))

                if $(aws ec2 describe-instance-status --instance-ids ${NEW_INSTANCE_ID} --query 'InstanceStatuses[0].InstanceStatus' --output text | grep -q "passed")
                then
                    echo "INFO: Instance has been initialized in approximately ${TIME_SPENT} seconds."
                    echo "WARN: Please change your default security group depending on the level of access you wish to enable."
                else
                    echo "ERROR : Instance initialization failed after ${TIME_SPENT} seconds. Please check the AWS console for more information."
                    exit 1
                fi

                if [ -f ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/ec2-extension.conf ]; then

                    echo "#######################################"
                    echo "INFO: Adding EC2 instance to NGINX config using xip.io..."

                    SERVICE_NAME="EC2-Service-Extension-${BUILD_NUMBER}"

                    ## Update nginx configuration
                    sed -i "s/###EC2_SERVICE_NAME###/${SERVICE_NAME}/" ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/ec2-extension.conf
                    sed -i "s/###EC2_HOST_IP###/${NODE_IP}/" ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/ec2-extension.conf

                    echo "INFO: You can check that your EC2 instance has been successfully proxied by accessing the following URL: ${SERVICE_NAME}.${PUBLIC_IP}.xip.io"
                else
                    echo "INFO: /service/${PLATFORM_EXTENSION_TYPE}/ec2-extension.conf not found"
                fi

            else
                echo "INFO: /service/${PLATFORM_EXTENSION_TYPE}/service.template not found"
            fi
        fi
       ;;
    docker)
        if [ -d ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE} ]; then

            SERVICE_NAME="Docker-Service-Extension-${BUILD_NUMBER}"

            docker-compose -f ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/docker-compose.yml -p ${SERVICE_NAME} up -d
        else
            echo "ERROR: /service/${PLATFORM_EXTENSION_TYPE} extension not found"
            exit 1
        fi
       ;;
    *) echo "ERROR: Platform extension type not found. See Job description or PLATFORM_EXTENSION_TYPE choice build parameter for supported types."
       exit 1
       ;;
esac

echo "INFO: Deploying proxy configuration."

RELOAD_PROXY=false
COUNT=0
for file in ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/*.conf
  do
    docker cp ${file} proxy:/etc/nginx/sites-enabled/${COUNT}-${SERVICE_NAME}.conf
    COUNT=$((COUNT))+1
    RELOAD_PROXY=true
done

# Deploy new contexts to the existing tools vhost by naming files with an .ext file extension.
COUNT=0
for file in ${WORKSPACE}/service/${PLATFORM_EXTENSION_TYPE}/*.ext
  do
    docker cp ${file} proxy:/etc/nginx/sites-enabled/service-extension/${COUNT}-${SERVICE_NAME}.conf
    COUNT=$((COUNT))+1
    RELOAD_PROXY=true
done

if [ "${RELOAD_PROXY}" = true ] ; then
    docker exec proxy /usr/sbin/nginx -s reload
fi

echo "INFO: Platform extension ${PLATFORM_EXTENSION_NAME} loaded."

# Note
# The idea with a service name is that it could be used by a "Destroy Platform Extension" Job as it acts as a unique ID for a platform extension.
# To delete a platform extension's proxy configuration delete all files that end in ${SERVICE_NAME}.{conf,ext}
echo "INFO: Service extension unique ID: ${SERVICE_NAME}"
''')
      configure{ // using configure as DSL does not support the PasswordParameterDefinition property type.
     		project ->
        	  project / 'properties' / 'hudson.model.ParametersDefinitionProperty' / 'parameterDefinitions' << 'hudson.model.PasswordParameterDefinition' {
         	    'name'('AWS_ACCESS_KEY_ID')
            	'default'('')
              'description'('AWS Access Key ID. Note: This parameter is only required for the AWS platform extension type.')
        	  }
        }
        configure{
          project ->
              project / 'properties' / 'hudson.model.ParametersDefinitionProperty' / 'parameterDefinitions' << 'hudson.model.PasswordParameterDefinition' {
                'name'('AWS_SECRET_ACCESS_KEY')
                'default'('')
                'description'('AWS Access Key ID. Note: This parameter is only required for the AWS platform extension type.')
              }
        }
    }
}
