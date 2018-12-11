.PHONY: build publish ci_build ci_test clean test inc-version

env:
	echo ${LIGHTSTEP_ENABLED} ${LIGHTSTEP_ACCESS_TOKEN}  ${LIGHTSTEP_VERBOSE}

build: test
	mvn package

clean:
	mvn clean

test: ci_build ci_test

ci_build:
	mvn install

# CircleCI test
ci_test:
	mvn exec:java -DLIGHTSTEP_ENABLED=${LIGHTSTEP_ENABLED} -DLIGHTSTEP_VERBOSE=${LIGHTSTEP_VERBOSE} -DLIGHTSTEP_ACCESS_TOKEN=${LIGHTSTEP_ACCESS_TOKEN} -pl examples

inc-version:
	./inc-version.sh

# See https://bintray.com/lightstep for published artifacts
# You must have the following entry in your settings.xml of your .m2 directory
# This matches the distributionManagement/repository defined in the pom.xml
#
#    <server>
#        <id>lightstep-bintray</id>
#        <username>xxx</username>
#        <password>xxx</password>
#    </server>
#
publish: build
	@test -n "$$BINTRAY_USER" || (echo "BINTRAY_USER must be defined to publish" && false)
	@test -n "$$BINTRAY_API_KEY" || (echo "BINTRAY_API_KEY must be defined to publish" && false)
	@test -n "$$MAVEN_CENTRAL_USER_TOKEN" || (echo "MAVEN_CENTRAL_USER_TOKEN must be defined to publish" && false)
	@test -n "$$MAVEN_CENTRAL_TOKEN_PASSWORD" || (echo "MAVEN_CENTRAL_TOKEN_PASSWORD must be defined to publish" && false)
	@test -n "$$BINTRAY_GPG_PASSPHRASE" || (echo "$$BINTRAY_GPG_PASSPHRASE must be defined to publish" && false)

	@git diff-index --quiet HEAD || (echo "git has uncommitted changes. Refusing to publish." && false)
	./tag-and-deploy.sh
