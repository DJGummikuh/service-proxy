/* Copyright 2009, 2011 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core;

import java.util.ArrayList;
import java.util.List;

import com.predic8.membrane.core.exchangestore.ForgetfulExchangeStore;
import com.predic8.membrane.core.interceptor.*;
import com.predic8.membrane.core.io.ConfigurationFileStore;
import com.predic8.membrane.core.transport.http.HttpTransport;

public class HttpRouter extends Router {

	public HttpRouter() {
		ruleManager = new RuleManager();
		ruleManager.setRouter(this);
		exchangeStore = new ForgetfulExchangeStore();
		transport = new HttpTransport();
		transport.setRouter(this);
		configurationManager = new ConfigurationManager();
		configurationManager.setConfigurationStore(new ConfigurationFileStore());
		configurationManager.setRouter(this);
		List<Interceptor> interceptors = new ArrayList<Interceptor>();
		interceptors.add(new RuleMatchingInterceptor());		
		interceptors.add(new DispatchingInterceptor());
		interceptors.add(new UserFeatureInterceptor());
		interceptors.add(new HTTPClientInterceptor());
		for (Interceptor i : interceptors) {
			i.setRouter(this);
		}
		transport.setInterceptors(interceptors);
	}
	
	@Override
	public HttpTransport getTransport() {
		return (HttpTransport)transport;
	}
	
}