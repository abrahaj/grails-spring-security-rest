package com.odobo.grails.plugin.springsecurity.rest

import groovy.util.logging.Log4j
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.filter.GenericFilterBean

import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest

/**
 * This filter starts the token validation flow. It extracts the token from the configured header name, and pass it to
 * the {@link RestAuthenticationProvider}.
 *
 * If successful, the result is stored in the security context and the response is generated by the
 * {@link AuthenticationSuccessHandler}. Otherwise, an {@link AuthenticationFailureHandler} is called.
 */
@Log4j
class RestTokenValidationFilter extends GenericFilterBean {

    String headerName

    RestAuthenticationProvider restAuthenticationProvider

    AuthenticationSuccessHandler authenticationSuccessHandler
    AuthenticationFailureHandler authenticationFailureHandler

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest servletRequest = request

        log.debug "Looking for a token value in the header '${headerName}'"
        String tokenValue = servletRequest.getHeader(headerName)

        if (tokenValue) {
            log.debug "Token found: ${tokenValue}"

            try {
                log.debug "Trying to authenticate the token"
                RestAuthenticationToken authenticationRequest = new RestAuthenticationToken(tokenValue)
                RestAuthenticationToken authenticationResult = restAuthenticationProvider.authenticate(authenticationRequest)

                if (authenticationResult.authenticated) {
                    log.debug "Token authenticated. Storing the authentication result in the security context"
                    log.debug "Authentication result: ${authenticationResult}"
                    SecurityContextHolder.context.setAuthentication(authenticationResult)

                    authenticationSuccessHandler.onAuthenticationSuccess(request, response, authenticationResult)

                    log.debug "Continuing the filter chain"
                    chain.doFilter(request, response)
                }

            } catch (AuthenticationException ae) {
                log.debug "Authentication failed: ${ae.message}"
                authenticationFailureHandler.onAuthenticationFailure(request, response, ae)
            }
        } else {
            log.debug "Token not found. Continuing the filter chain"
            chain.doFilter(request, response)
        }

    }
}
