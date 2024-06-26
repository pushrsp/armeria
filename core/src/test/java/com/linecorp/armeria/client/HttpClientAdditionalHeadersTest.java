/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.client.UserAgentUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class HttpClientAdditionalHeadersTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(req.headers().toString()));
        }
    };

    @Test
    void disallowedHeadersMustBeFiltered() {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             ctx.setAdditionalRequestHeader(HttpHeaderNames.SCHEME, "https");
                             ctx.addAdditionalRequestHeader(HttpHeaderNames.STATUS, "503");
                             ctx.mutateAdditionalRequestHeaders(
                                     mutator -> mutator.add(HttpHeaderNames.METHOD, "CONNECT"));
                             ctx.mutateAdditionalRequestHeaders(
                                     mutator -> mutator.add("foo", "bar"));
                             return delegate.execute(ctx, req);
                         })
                         .build();

        assertThat(client.get("/").aggregate().join().contentUtf8())
                .doesNotContain("=https")
                .doesNotContain("=503")
                .doesNotContain("=CONNECT")
                .contains("foo=bar");
    }

    @Test
    void authorityOverriddenCorrectly() throws Exception {
        final String authority = "custom.authority";
        try (ClientRequestContextCaptor clientCaptor = Clients.newContextCaptor()) {
            assertThat(server.blockingWebClient(cb -> cb.decorator((delegate, ctx, req) -> {
                                 ctx.setAdditionalRequestHeader(HttpHeaderNames.AUTHORITY, authority);
                                 return delegate.execute(ctx, req);
                             }))
                             .get("/").status().code()).isEqualTo(200);

            assertThat(clientCaptor.size()).isEqualTo(1);
            final ClientRequestContext clientContext = clientCaptor.get();
            assertThat(clientContext.authority()).isEqualTo(authority);
            assertThat(clientContext.log().whenComplete().join().requestHeaders()
                                    .get(HttpHeaderNames.USER_AGENT))
                    .isEqualTo(UserAgentUtil.USER_AGENT.toString());

            final ServiceRequestContextCaptor serviceCaptor = server.requestContextCaptor();
            assertThat(serviceCaptor.size()).isEqualTo(1);
            final ServiceRequestContext serviceContext = serviceCaptor.poll();
            assertThat(serviceContext.request().authority()).isEqualTo(authority);
            assertThat(serviceContext.request().headers().get(HttpHeaderNames.USER_AGENT))
                    .isEqualTo(UserAgentUtil.USER_AGENT.toString());
        }
    }
}
