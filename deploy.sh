# Just use
# mvn install -DcreateChecksum=true
# this command is not useful
mvn deploy:deploy-file -DcreateChecksum=true -Dfile=target/mylive.jar -Dpackaging=jar -DgroupId=com.longyb -Dversion=0.0.1 -DartifactId=mylive -Durl=file:local-repo
