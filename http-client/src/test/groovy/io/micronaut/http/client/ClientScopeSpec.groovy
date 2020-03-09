/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.client

import com.fasterxml.jackson.databind.DeserializationFeature
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientException
import io.micronaut.http.client.exceptions.NoHostException
import io.micronaut.http.client.netty.DefaultHttpClient
import io.micronaut.jackson.annotation.JacksonFeatures
import io.micronaut.runtime.server.EmbeddedServer
import io.reactivex.Flowable
import spock.lang.Retry
import spock.lang.Specification

import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author Graeme Rocher
 * @since 1.0
 */
@Retry(mode = Retry.Mode.SETUP_FEATURE_CLEANUP)
class ClientScopeSpec extends Specification {

    EmbeddedServer embeddedServer
    ApplicationContext applicationContext

    void setup() {
        embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'spec.name': 'ClientScopeSpec',
                'from.config': '/',
                'micronaut.server.port':'${random.port}',
                'micronaut.http.services.my-service.url': 'http://localhost:${micronaut.server.port}',
                'micronaut.http.services.my-service-declared.url': 'http://my-service-declared:${micronaut.server.port}',
                'micronaut.http.services.my-service-declared.path': "/my-declarative-client-path",
                'micronaut.http.services.other-service.url': 'http://localhost:${micronaut.server.port}',
                'micronaut.http.services.other-service.path': "/scope"])

        applicationContext = embeddedServer.applicationContext
    }

    void cleanup() {
        embeddedServer.close()
    }

    void "test client scope annotation method injection"() {
        given:
        MyService myService = applicationContext.getBean(MyService)

        MyJavaService myJavaService = applicationContext.getBean(MyJavaService)

        expect:
        myService.get() == 'success'
        myJavaService.client == myService.client
        myJavaService.rxHttpClient == myService.rxHttpClient

        cleanup:
        embeddedServer.close()
    }

    void "test client scope annotation field injection"() {
        given:
        MyServiceField myService = applicationContext.getBean(MyServiceField)

        MyJavaService myJavaService = applicationContext.getBean(MyJavaService)

        expect:
        myService.get() == 'success'
        myJavaService.client == myService.client
        myJavaService.rxHttpClient == myService.rxHttpClient
    }

    void "test client scope annotation constructor injection"() {
        given:
        MyServiceConstructor myService = applicationContext.getBean(MyServiceConstructor)

        MyJavaService myJavaService = applicationContext.getBean(MyJavaService)

        expect:
        myService.get() == 'success'
        myJavaService.client == myService.client
        myJavaService.rxHttpClient == myService.rxHttpClient
    }

    void "test client scope with path in annotation"() {
        given:
        MyService myService = applicationContext.getBean(MyService)
        MyServiceField myServiceField = applicationContext.getBean(MyServiceField)

        expect:
        myService.getPath() == 'success'
        myServiceField.getPath() == 'success'
    }

    void "test injection after declarative client"() {
        given:
        MyDeclarativeService client = applicationContext.getBean(MyDeclarativeService)

        when:
        client.name()

        then:
        thrown(HttpClientException)

        when:
        MyJavaService myJavaService = applicationContext.getBean(MyJavaService)

        then:
        Flowable.fromPublisher(((DefaultHttpClient) myJavaService.client)
                .resolveRequestURI(HttpRequest.GET("/foo"))).blockingFirst().toString() == "http://localhost:${embeddedServer.port}/foo"
    }

    void "test service definition with declarative client with jackson features"() {
        MyServiceJacksonFeatures client = applicationContext.getBean(MyServiceJacksonFeatures)

        expect:
        client.name() == "success"
    }

    void "test no base path with the declarative client"() {
        NoBasePathService client = applicationContext.getBean(NoBasePathService)

        expect:
        client.name("http://localhost:${embeddedServer.port}/scope") == "success"

        when:
        client.name("/scope")

        then:
        def ex = thrown(NoHostException)
        ex.message == "Request URI specifies no host to connect to"
    }

    void "test no base path with client scope"() {
        RxHttpClient client = applicationContext.getBean(MyService).noIdClient

        expect:
        client.toBlocking().retrieve("http://localhost:${embeddedServer.port}/scope") == "success"

        when:
        client.toBlocking().retrieve("/scope")

        then:
        def ex = thrown(NoHostException)
        ex.message == "Request URI specifies no host to connect to"
    }

    void "test injected instances are different/same"() {
        InstanceEquals bean = applicationContext.getBean(InstanceEquals)
        InstanceDoesNotEqual bean2 = applicationContext.getBean(InstanceDoesNotEqual)

        expect:
        bean.client.is(bean.client2)
        !bean.client.is(bean2.client) //value is different
        !bean.client2.is(bean2.client2) //bean2 has configuration

        bean.clientId.is(bean.clientId2)
        !bean.clientId.is(bean2.clientId) //id is different
        !bean.clientId2.is(bean2.clientId2) //bean2 has path
        !bean.clientId2.is(bean2.clientId3) //bean2 has configuration

        bean.clientIdPath.is(bean.clientIdPath2)
        !bean.clientIdPath.is(bean2.clientId) // path is different

        bean.clientConfiguration.is(bean.clientConfiguration2)
        !bean.clientConfiguration.is(bean2.client2) // configuration is different

        bean.rxClient.is(bean.client)
    }

    @Controller('/scope')
    static class ScopeController {
        @Get(produces = MediaType.TEXT_PLAIN)
        String index() {
            return "success"
        }
    }

    @Singleton
    static class MyService {

        @Inject @Client('${from.config}')
        HttpClient client

        @Inject @Client('${from.config}')
        RxHttpClient rxHttpClient

        @Inject @Client(id = 'myService', path = '/scope')
        RxHttpClient pathClient

        @Inject @Client
        RxHttpClient noIdClient

        String get() {
            rxHttpClient != null
            client.toBlocking().retrieve(
                    HttpRequest.GET('/scope'), String
            )
        }

        String getPath() {
            pathClient.toBlocking().retrieve("/", String)
        }
    }

    @Singleton
    static class MyServiceField {

        @Inject @Client('${from.config}')
        protected HttpClient client

        @Inject @Client('${from.config}')
        protected RxHttpClient rxHttpClient

        @Inject @Client(id = 'myService', path = '/scope')
        protected RxHttpClient pathClient

        String get() {
            rxHttpClient != null
            client.toBlocking().retrieve(
                    HttpRequest.GET('/scope'), String
            )
        }

        String getPath() {
            pathClient.toBlocking().retrieve("/", String)
        }
    }

    @Singleton
    static class MyServiceConstructor {

        private final HttpClient client
        private final RxHttpClient rxHttpClient

        MyServiceConstructor(@Client('${from.config}')HttpClient client,
                             @Client('${from.config}') RxHttpClient rxHttpClient) {
            this.rxHttpClient = rxHttpClient
            this.client = client
        }

        String get() {
            rxHttpClient != null
            client.toBlocking().retrieve(
                    HttpRequest.GET('/scope'), String
            )
        }
    }

    @Singleton
    static class InstanceEquals {

        @Inject @Client('/')
        protected HttpClient client

        @Inject @Client('/')
        protected HttpClient client2

        @Inject @Client(id = "bar")
        protected HttpClient clientId

        @Inject @Client(id = "bar")
        protected HttpClient clientId2

        @Inject @Client(id = "bar", path = "/bar")
        protected HttpClient clientIdPath

        @Inject @Client(id = "bar", path = "/bar")
        protected HttpClient clientIdPath2

        @Inject @Client(value = '/', configuration = DefaultHttpClientConfiguration)
        protected HttpClient clientConfiguration

        @Inject @Client(value = '/', configuration = DefaultHttpClientConfiguration)
        protected HttpClient clientConfiguration2

        @Inject @Client('/')
        protected RxHttpClient rxClient
    }

    @Singleton
    static class InstanceDoesNotEqual {

        @Inject @Client('/foo')
        protected HttpClient client

        @Inject @Client(value = '/', configuration = CustomConfig)
        protected HttpClient client2

        @Inject @Client(id = "foo")
        protected HttpClient clientId

        @Inject @Client(id = "bar", path = "/foo")
        protected HttpClient clientId2

        @Inject @Client(id = "bar", configuration = CustomConfig)
        protected HttpClient clientId3
    }

    @Singleton
    @ConfigurationProperties("custom")
    static class CustomConfig extends DefaultHttpClientConfiguration {

    }

    @Requires(property = 'spec.name', value = "ClientScopeSpec")
    @Client(id = 'my-service-declared')
    static interface MyDeclarativeService {

        @Get
        String name()
    }

    @Requires(property = 'spec.name', value = "ClientScopeSpec")
    @Client(id = 'other-service')
    @JacksonFeatures(disabledDeserializationFeatures = DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    static interface MyServiceJacksonFeatures {

        @Get(consumes = MediaType.TEXT_PLAIN)
        String name()
    }

    @Requires(property = 'spec.name', value = "ClientScopeSpec")
    @Client
    static interface NoBasePathService {

        @Get(value = "{+uri}", consumes = MediaType.TEXT_PLAIN)
        String name(String uri)
    }
}
