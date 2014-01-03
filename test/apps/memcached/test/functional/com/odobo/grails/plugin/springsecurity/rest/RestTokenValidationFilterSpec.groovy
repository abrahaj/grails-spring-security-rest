package com.odobo.grails.plugin.springsecurity.rest

import grails.plugins.rest.client.RestBuilder
import grails.plugins.rest.client.RestResponse
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class RestTokenValidationFilterSpec extends Specification {

    @Shared
    RestBuilder restBuilder = new RestBuilder()

    @Shared
    String baseUrl = "http://localhost:8080/memcached"

    void "accessing a secured controller without token returns 403"() {
        when:
        def response = restBuilder.get("${baseUrl}/secured")

        then:
        response.status == 403
    }

    void "a valid user can access the secured controller"() {
        given:
        RestResponse authResponse = restBuilder.post("${baseUrl}/login?username=jimi&password=jimispassword")
        String token = authResponse.json.token

        when:
        def response = restBuilder.get("${baseUrl}/secured") {
            header 'X-Auth-Token', token
        }

        then:
        response.status == 200
        response.text == 'jimi'
    }

    void "role restrictions are applied when user does not have enough credentials"() {
        given:
        RestResponse authResponse = restBuilder.post("${baseUrl}/login?username=jimi&password=jimispassword")
        String token = authResponse.json.token

        when:
        def response = restBuilder.get("${baseUrl}/secured/superAdmin") {
            header 'X-Auth-Token', token
        }

        then:
        response.status == 403
    }

}
