import com.odobo.grails.plugin.springsecurity.rest.*
import com.odobo.grails.plugin.springsecurity.rest.credentials.DefaultJsonPayloadCredentialsExtractor
import com.odobo.grails.plugin.springsecurity.rest.credentials.RequestParamsCredentialsExtractor
import com.odobo.grails.plugin.springsecurity.rest.oauth.DefaultOauthUserDetailsService
import com.odobo.grails.plugin.springsecurity.rest.token.generation.SecureRandomTokenGenerator
import com.odobo.grails.plugin.springsecurity.rest.token.rendering.DefaultRestAuthenticationTokenJsonRenderer
import com.odobo.grails.plugin.springsecurity.rest.token.storage.GormTokenStorageService
import com.odobo.grails.plugin.springsecurity.rest.token.storage.MemcachedTokenStorageService
import com.odobo.grails.plugin.springsecurity.rest.token.storage.GrailsCacheTokenStorageService
import grails.plugin.springsecurity.SecurityFilterPosition
import grails.plugin.springsecurity.SpringSecurityUtils
import net.spy.memcached.DefaultHashAlgorithm
import net.spy.memcached.spring.MemcachedClientFactoryBean
import net.spy.memcached.transcoders.SerializingTranscoder
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.access.ExceptionTranslationFilter
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint
import org.springframework.security.web.authentication.NullRememberMeServices
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.savedrequest.NullRequestCache

import javax.servlet.http.HttpServletResponse

class SpringSecurityRestGrailsPlugin {

    String version = "1.3.4"
    String grailsVersion = "2.0 > *"
    List loadAfter = ['springSecurityCore']
    List pluginExcludes = [
        "grails-app/views/**"
    ]

    String title = "Spring Security REST Plugin"
    String author = "Alvaro Sanchez-Mariscal"
    String authorEmail = "alvaro.sanchez@odobo.com"
    String description = 'Implements authentication for REST APIs based on Spring Security. It uses a token-based workflow'

    String documentation = "http://alvarosanchez.github.io/grails-spring-security-rest/"

    String license = "APACHE"
    def organization = [ name: "Odobo Limited", url: "http://www.odobo.com" ]

    def issueManagement = [ system: "GitHub", url: "https://github.com/alvarosanchez/grails-spring-security-rest/issues" ]
    def scm = [ url: "https://github.com/alvarosanchez/grails-spring-security-rest" ]

    def doWithSpring = {


        def conf = SpringSecurityUtils.securityConfig
        if (!conf || !conf.active) {
            return
        }

        SpringSecurityUtils.loadSecondaryConfig 'DefaultRestSecurityConfig'
        conf = SpringSecurityUtils.securityConfig

        if (!conf.rest.active) {
            return
        }

        boolean printStatusMessages = (conf.printStatusMessages instanceof Boolean) ? conf.printStatusMessages : true

        if (printStatusMessages) {
            println '\nConfiguring Spring Security REST ...'
        }

        ///*
        SpringSecurityUtils.registerProvider 'restAuthenticationProvider'

        /* restAuthenticationFilter */
        if(conf.rest.login.active) {
            SpringSecurityUtils.registerFilter 'restAuthenticationFilter', SecurityFilterPosition.FORM_LOGIN_FILTER.order + 1
            SpringSecurityUtils.registerFilter 'restLogoutFilter', SecurityFilterPosition.LOGOUT_FILTER.order - 1

            restAuthenticationFilter(RestAuthenticationFilter) {
                authenticationManager = ref('authenticationManager')
                authenticationSuccessHandler = ref('restAuthenticationSuccessHandler')
                authenticationFailureHandler = ref('restAuthenticationFailureHandler')
                authenticationDetailsSource = ref('authenticationDetailsSource')
                credentialsExtractor = ref('credentialsExtractor')
                endpointUrl = conf.rest.login.endpointUrl
                tokenGenerator = ref('tokenGenerator')
                tokenStorageService = ref('tokenStorageService')
            }

            def paramsClosure = {
                usernameParameter = conf.rest.login.usernameParameter // username
                passwordParameter = conf.rest.login.passwordParameter // password
            }

            if (conf.rest.login.useRequestParamsCredentials) {
                credentialsExtractor(RequestParamsCredentialsExtractor, paramsClosure)
            } else if (conf.rest.login.useJsonCredentials) {
                credentialsExtractor(DefaultJsonPayloadCredentialsExtractor, paramsClosure)
            } else {
                println """WARNING: credentials extraction strategy is not explicitly set. Falling back to JSON.
Please, read http://alvarosanchez.github.io/grails-spring-security-rest/docs/guide/authentication.html for more details"""
                credentialsExtractor(DefaultJsonPayloadCredentialsExtractor, paramsClosure)
            }

            /* restLogoutFilter */
            restLogoutFilter(RestLogoutFilter) {
                endpointUrl = conf.rest.logout.endpointUrl
                headerName = conf.rest.token.validation.headerName
                tokenStorageService = ref('tokenStorageService')
            }
        }

        /* restAuthenticationTokenJsonRenderer */
        restAuthenticationTokenJsonRenderer(DefaultRestAuthenticationTokenJsonRenderer)

        restAuthenticationSuccessHandler(RestAuthenticationSuccessHandler) {
            renderer = ref('restAuthenticationTokenJsonRenderer')
        }
        restAuthenticationFailureHandler(RestAuthenticationFailureHandler) {
            statusCode = conf.rest.login.failureStatusCode?:HttpServletResponse.SC_UNAUTHORIZED
        }

        /* restTokenValidationFilter */
        SpringSecurityUtils.registerFilter 'restTokenValidationFilter', SecurityFilterPosition.ANONYMOUS_FILTER.order + 1
        SpringSecurityUtils.registerFilter 'restExceptionTranslationFilter', SecurityFilterPosition.EXCEPTION_TRANSLATION_FILTER.order - 5

        restTokenValidationFilter(RestTokenValidationFilter) {
            headerName = conf.rest.token.validation.headerName
            validationEndpointUrl = conf.rest.token.validation.endpointUrl
            active = conf.rest.token.validation.active
            useBearerToken = conf.rest.token.validation.useBearerToken ?: false
            authenticationSuccessHandler = ref('restAuthenticationSuccessHandler')
            authenticationFailureHandler = ref('restAuthenticationFailureHandler')
            restAuthenticationProvider = ref('restAuthenticationProvider')
        }

        restExceptionTranslationFilter(ExceptionTranslationFilter, ref('restAuthenticationEntryPoint'), ref('restRequestCache')) {
            accessDeniedHandler = ref('restAccessDeniedHandler')
            authenticationTrustResolver = ref('authenticationTrustResolver')
            throwableAnalyzer = ref('throwableAnalyzer')
        }

        restAuthenticationEntryPoint(Http403ForbiddenEntryPoint)
        restRequestCache(NullRequestCache)
        restAccessDeniedHandler(AccessDeniedHandlerImpl) {
            errorPage = null //403
        }

        /* tokenStorageService */
        if (conf.rest.token.storage.useMemcached) {

            Properties systemProperties = System.properties
            systemProperties.put("net.spy.log.LoggerImpl", "net.spy.memcached.compat.log.Log4JLogger")
            System.setProperties(systemProperties)

            memcachedClient(MemcachedClientFactoryBean) {
                servers = conf.rest.token.storage.memcached.hosts
                protocol = 'BINARY'
                transcoder = { SerializingTranscoder st ->
                    compressionThreshold = 1024
                }
                opTimeout = 1000
                timeoutExceptionThreshold = 1998
                hashAlg = DefaultHashAlgorithm.KETAMA_HASH
                locatorType = 'CONSISTENT'
                failureMode = 'Redistribute'
                useNagleAlgorithm = false
            }

            tokenStorageService(MemcachedTokenStorageService) {
                memcachedClient = ref('memcachedClient')
                expiration = conf.rest.token.storage.memcached.expiration
            }
        } else if (conf.rest.token.storage.useGrailsCache) {
            tokenStorageService(GrailsCacheTokenStorageService) {
                grailsCacheManager = ref('grailsCacheManager')
                cacheName = conf.rest.token.storage.grailsCacheName
            }
        } else if (conf.rest.token.storage.useGorm) {
            tokenStorageService(GormTokenStorageService) {
                userDetailsService = ref('userDetailsService')
            }
        } else {
            println """WARNING: token storage strategy is not explicitly set. Falling back to GORM.
Please, read http://alvarosanchez.github.io/grails-spring-security-rest/docs/guide/tokenStorage.html for more details"""
            tokenStorageService(GormTokenStorageService) {
                userDetailsService = ref('userDetailsService')
            }
        }

        /* tokenGenerator */
        tokenGenerator(SecureRandomTokenGenerator)

        /* restAuthenticationProvider */
        restAuthenticationProvider(RestAuthenticationProvider) {
            tokenStorageService = ref('tokenStorageService')
        }

        /* oauthUserDetailsService */
        oauthUserDetailsService(DefaultOauthUserDetailsService) {
            userDetailsService = ref('userDetailsService')
        }

        //*/

        if (printStatusMessages) {
            println '... finished configuring Spring Security REST\n'
        }

    }


}
