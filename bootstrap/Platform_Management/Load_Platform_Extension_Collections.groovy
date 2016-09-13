// Constants
def platformManagementFolderName= "/Platform_Management"
def platformManagementFolder = folder(platformManagementFolderName) { displayName('Platform Management') }

// Jobs
def loadPlatformExtensionCollectionJob = workflowJob(platformManagementFolderName + "/Load_Platform_Extension_Collection")

// Setup Load_Platform_Extension_Collection job
loadPlatformExtensionCollectionJob.with{
    parameters{
        stringParam('COLLECTION_URL', '', 'URL to a JSON file defining your platform extension collection.')
    }
    properties {
        rebuild {
            autoRebuild(false)
            rebuildDisabled(false)
        }
    }
	definition {
        cps {
            script('''node {

    sh("wget ${COLLECTION_URL} -O collection.json")

    println "Reading in values from file..."
    Map data = parseJSON(readFile('collection.json'))

    println(data);
    println "Obtained values locally...";

    extensionCount = data.extensions.size
    println "Number of platform extensions: ${extensionCount}"

    // For loop iterating over the data map obtained from the provided JSON file
    for ( i = 0 ; i < extensionCount ; i++ ) {
        String url = data.extensions[i].url
        println("Platform Extension URL: " + url)
        String desc = data.extensions[i].description
        String extType = data.extensions[i].extension_type
        build job: '/Platform_Management/Load_Platform_Extension', parameters: [[$class: 'StringParameterValue', name: 'PLATFORM_EXTENSION_TYPE', value: extType], [$class: 'StringParameterValue', name: 'GIT_URL', value: url], [$class: 'StringParameterValue', name: 'GIT_REF', value: 'master'], [$class: 'StringParam', name: 'AWS_ACCESS_KEY_ID', value: "${AWS_ACCESS_KEY_ID}"], [$class: 'StringParam', name: 'AWS_SECRET_ACCESS_KEY', value: "${AWS_SECRET_ACCESS_KEY}"]]
    }
}

@NonCPS
    def parseJSON(text) {
    def slurper = new groovy.json.JsonSlurper();
    Map data = slurper.parseText(text)
    slurper = null
    return data
}
            ''')
            sandbox()
        }
    }
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
