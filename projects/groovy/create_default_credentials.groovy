import jenkins.model.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*
import hudson.plugins.sshslaves.*;

def add_username_password_credential(id, username, password, description){
  def domain = Domain.global()
  def store = Jenkins.instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()

  def defaultUsernameAndPassword = new UsernamePasswordCredentialsImpl(
    CredentialsScope.GLOBAL,
    id,
    description,
    username,
    password
  )
  store.addCredentials(domain, defaultUsernameAndPassword)
}

add_username_password_credential("adop-default","adop-default", "adop-default","Default credentials for Jenkins. This credential does not grant access to any service." )
