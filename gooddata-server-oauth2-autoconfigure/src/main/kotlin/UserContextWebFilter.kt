/*
 * Copyright 2021 GoodData Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gooddata.oauth2.server

import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * [WebFilter] that creates user context and stores it to coroutine/reactor context.
 *
 * There are multiple scenarios how user context is created. If [SecurityContext] contains
 * [UserContextAuthenticationToken] it is simply extracted and user context is created according to its content.
 *
 * In case [OAuth2AuthenticationToken] is used `UserContextWebFilter` retrieves [Organization] based on request's
 * hostname and [User] that corresponds to `SUB` attribute from ID token. If the [User] cannot be found the current
 * session is terminated and new authentication flow is triggered.
 *
 * [UserContextWebFilter] also handles global logout from all user sessions. Request's [OAuth2AuthenticationToken] is
 * compared to stored last global logout timestamp and if the token has been issued before stored date
 * current session is terminated, logout is trigger and new authentication initialization is initiated.
 */
class UserContextWebFilter(
    private val client: AuthenticationStoreClient,
    private val authenticationEntryPoint: ServerAuthenticationEntryPoint,
    private val serverLogoutHandler: ServerLogoutHandler,
    private val reactorUserContextProvider: ReactorUserContextProvider,
) : WebFilter {

    private val logger = KotlinLogging.logger {}

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder.getContext()
            .switchIfEmpty(Mono.just(SecurityContextImpl(UnauthenticatedResourceAuthentication)))
            .flatMap { securityContext ->
                when (val authentication = securityContext.authentication) {
                    UnauthenticatedResourceAuthentication -> {
                        logger.debug { "Security context is not set, probably accessing unauthenticated resource" }
                        chain.filter(exchange)
                    }
                    null -> {
                        logger.warn { "Security cannot be retrieved" }
                        chain.filter(exchange)
                    }
                    else -> authentication.process(exchange, chain)
                }
            }

    private fun Authentication.process(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> = when (this) {
        is OAuth2AuthenticationToken -> processAuthenticationToken(this, exchange, chain)
        is UserContextAuthenticationToken -> withUserContext(organization, user, null) {
            chain.filter(exchange)
        }
        else -> {
            logger.warn { "Security context contains unexpected authentication ${this::class}" }
            chain.filter(exchange)
        }
    }

    private fun processAuthenticationToken(
        auth: OAuth2AuthenticationToken,
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> = mono {
        getUserContextForAuthenticationToken(client, auth)
    }.flatMap { userContext ->
        if (userContext.user == null) {
            logger.info { "Session was logged out" }
            serverLogoutHandler.logout(WebFilterExchange(exchange, chain), auth).then(
                if (userContext.restartAuthentication) {
                    authenticationEntryPoint.commence(exchange, null)
                } else {
                    Mono.error(ResponseStatusException(HttpStatus.NOT_FOUND, "User is not registered"))
                }
            )
        } else {
            withUserContext(userContext.organization, userContext.user, auth.name) {
                chain.filter(exchange)
            }
        }
    }

    private fun <T> withUserContext(
        organization: Organization,
        user: User,
        name: String?,
        monoProvider: () -> Mono<T>,
    ): Mono<T> = monoProvider().contextWrite(reactorUserContextProvider.getContextView(organization.id, user.id, name))

    /**
     * A helper [Authentication] type for representing the empty authentication of unauthenticated resources.
     *
     * This allows us to handle all cases (unauthenticated resource, missing authentication in authenticated resource
     * and proper authentication in authenticated resource) together.
     */
    private object UnauthenticatedResourceAuthentication : AbstractAuthenticationToken(emptyList()) {
        override fun getCredentials() = null
        override fun getPrincipal() = null
    }
}