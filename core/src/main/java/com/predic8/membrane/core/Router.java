/* Copyright 2009, 2012 predic8 GmbH, www.predic8.com

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

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.Lifecycle;
import org.springframework.context.support.AbstractRefreshableApplicationContext;

import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.annot.MCChildElement;
import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.annot.MCMain;
import com.predic8.membrane.core.RuleManager.RuleDefinitionSource;
import com.predic8.membrane.core.exchangestore.ExchangeStore;
import com.predic8.membrane.core.exchangestore.LimitedMemoryExchangeStore;
import com.predic8.membrane.core.interceptor.Interceptor;
import com.predic8.membrane.core.resolver.ResolverMap;
import com.predic8.membrane.core.rules.Rule;
import com.predic8.membrane.core.rules.SOAPProxy;
import com.predic8.membrane.core.rules.ServiceProxy;
import com.predic8.membrane.core.transport.Transport;
import com.predic8.membrane.core.transport.http.HttpServerThreadFactory;
import com.predic8.membrane.core.transport.http.HttpTransport;
import com.predic8.membrane.core.transport.http.client.HttpClientConfiguration;
import com.predic8.membrane.core.util.DNSCache;

@MCMain(
		outputPackage="com.predic8.membrane.core.config.spring",
		outputName="router-conf.xsd",
		targetNamespace="http://membrane-soa.org/proxies/1/")
@MCElement(name="router", group="basic")
public class Router implements Lifecycle, ApplicationContextAware {

	private static final Log log = LogFactory.getLog(Router.class.getName());

	/**
	 * In case more than one <router hotDeploy="true" /> starts within the same
	 * app context, we track them here, so they start only one
	 * HotDeploymentThread.
	 */
	protected static final HashSet<ApplicationContext> hotDeployingContexts = new HashSet<ApplicationContext>();

	private ApplicationContext beanFactory;

	protected RuleManager ruleManager = new RuleManager();
	protected ExchangeStore exchangeStore;
	protected Transport transport;
	protected ResolverMap resolverMap = new ResolverMap();
	protected DNSCache dnsCache = new DNSCache();
	protected ExecutorService backgroundInitializator = 
			Executors.newSingleThreadExecutor(new HttpServerThreadFactory("Router Background Initializator"));
	protected HotDeploymentThread hdt;
	
	private boolean hotDeploy = true;
	private boolean running;

	private int retryInitInterval = 5 * 60 * 1000; // 5 minutes
	private Timer reinitializator;

	public Router() {
		ruleManager.setRouter(this);
	}

	public Collection<Rule> getRules() {
		return getRuleManager().getRulesBySource(RuleDefinitionSource.SPRING);
	}
	
	@MCChildElement(order=3)
	public void setRules(Collection<Rule> proxies) {
		for (Rule rule : proxies)
			getRuleManager().addProxy(rule, RuleDefinitionSource.SPRING);
	}

	public static Router init(String configFileName)
			throws MalformedURLException {
		log.debug("loading spring config from classpath: " + configFileName);
		return init(configFileName, Router.class.getClassLoader());
	}

	public static Router init(String resource, ClassLoader classLoader) {
		log.debug("loading spring config: " + resource);

		TrackingFileSystemXmlApplicationContext beanFactory = 
				new TrackingFileSystemXmlApplicationContext(new String[] { resource }, false);
		beanFactory.setClassLoader(classLoader);
		beanFactory.refresh();
		
		beanFactory.start();

		return (Router) beanFactory.getBean("router");
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		beanFactory = applicationContext;
	}
	
	public RuleManager getRuleManager() {
		return ruleManager;
	}

	public void setRuleManager(RuleManager ruleManager) {
		this.ruleManager = ruleManager;
		ruleManager.setRouter(this);
	}

	public ExchangeStore getExchangeStore() {
		return exchangeStore;
	}

	@MCAttribute
	public void setExchangeStore(ExchangeStore exchangeStore) {
		this.exchangeStore = exchangeStore;
	}

	public Transport getTransport() {
		return transport;
	}

	@MCChildElement(order=1, allowForeign=true)
	public void setTransport(Transport transport) {
		this.transport = transport;
	}

	public HttpClientConfiguration getHttpClientConfig() {
		return resolverMap.getHTTPSchemaResolver().getHttpClientConfig();
	}
	
	@MCChildElement(order=0)
	public void setHttpClientConfig(HttpClientConfiguration httpClientConfig) {
		resolverMap.getHTTPSchemaResolver().setHttpClientConfig(httpClientConfig);
	}
	
	public DNSCache getDnsCache() {
		return dnsCache;
	}

	public ResolverMap getResolverMap() {
		return resolverMap;
	}

	/**
	 * Closes all ports (if any were opened) and waits for running exchanges to complete.
	 * 
	 * When running as an embedded servlet, this has no effect.
	 */
	public void shutdown() throws IOException {
		backgroundInitializator.shutdown();
		getTransport().closeAll();
	}
	
	/**
	 * Closes all ports (if any were opened), but does not wait for running exchanges to complete.
	 */
	public void shutdownNoWait() throws IOException {
		backgroundInitializator.shutdown();
		getTransport().closeAll(false);
	}
	
	public ExecutorService getBackgroundInitializator() {
		return backgroundInitializator;
	}
	
	public Rule getParentProxy(Interceptor interceptor) {
		for (Rule r : getRuleManager().getRules()) {
			for (Interceptor i : r.getInterceptors())
				if (i == interceptor)
					return r;
		}
		throw new IllegalArgumentException("No parent proxy found for the given interceptor.");
	}

    public void add(ServiceProxy serviceProxy) throws IOException {
        ruleManager.addProxyAndOpenPortIfNew(serviceProxy);
    }

	public void init() throws Exception {
		for (Rule rule : getRuleManager().getRules())
			rule.init(this);
		transport.init(this);
	}

	@Override
	public void start() {
		log.info("Starting " + Constants.PRODUCT_NAME + " " + Constants.VERSION);
		try {
			if (transport == null && beanFactory.getBeansOfType(Transport.class).values().size() > 0)
				throw new RuntimeException("unclaimed transport detected. - please migrate to 4.0");
			if (exchangeStore == null)
				exchangeStore = new LimitedMemoryExchangeStore();
			if (transport == null)
				transport = new HttpTransport();
			
			init();
			getRuleManager().openPorts();

			try {
				if (hotDeploy)
					startHotDeployment();
			} catch (Exception e) {
				shutdownNoWait();
				throw e;
			}
			
			if (retryInitInterval > 0)
				startAutoReinitializator();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		running = true;
	}

	private void startHotDeployment() {
		if (hdt != null)
			throw new IllegalStateException("Hot deployment already started.");
		if (!(beanFactory instanceof TrackingApplicationContext))
			throw new RuntimeException("ApplicationContext is not a TrackingApplicationContext. Please set <router hotDeploy=\"false\">.");
		if (!(beanFactory instanceof AbstractRefreshableApplicationContext))
			throw new RuntimeException("ApplicationContext is not a AbstractRefreshableApplicationContext. Please set <router hotDeploy=\"false\">.");
		synchronized (hotDeployingContexts) {
			if (hotDeployingContexts.contains(beanFactory))
				return;
			hotDeployingContexts.add(beanFactory);
		}
		hdt = new HotDeploymentThread((AbstractRefreshableApplicationContext) beanFactory);
		hdt.setFiles(((TrackingApplicationContext) beanFactory).getFiles());
		hdt.start();
	}

	private void stopHotDeployment() {
		stopAutoReinitializator();
		if (hdt != null) {
			hdt.stopASAP();
			hdt = null;
			synchronized (hotDeployingContexts) {
				hotDeployingContexts.remove(beanFactory);
			}
		}
	}
	
	private void startAutoReinitializator() {
		if (getInactiveRules().size() == 0)
			return;
		
		reinitializator = new Timer("auto reinitializator", true);
		reinitializator.schedule(new TimerTask() {
			@Override
			public void run() {
				boolean stillFailing = false;
				ArrayList<Rule> inactive = getInactiveRules();
				if (inactive.size() > 0) {
					log.info("Trying to activate all inactive rules.");
					for (Rule rule : inactive) {
						try {
							Rule newRule = ((SOAPProxy) rule).clone();
							if (!newRule.isActive()) {
								log.info("New rule is still not active.");
								stillFailing = true;
							}
							getRuleManager().replaceRule(rule, newRule);
						} catch (CloneNotSupportedException e) {
							e.printStackTrace();
						}
					}
				}
				if (stillFailing)
					log.info("There are still inactive rules.");
				else {
					stopAutoReinitializator();
					log.info("All rules have been initialized.");
				}
			}
		}, retryInitInterval, retryInitInterval);
	}
	
	private void stopAutoReinitializator() {
		Timer reinitializator2 = reinitializator;
		if (reinitializator2 != null) {
			reinitializator2.cancel();
		}
	}
	
	@Override
	public void stop() {
		try {
			if (hdt != null)
				stopHotDeployment();
			shutdown();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}
	
	@MCAttribute
	public void setHotDeploy(boolean hotDeploy) {
		if (running) {
			if (this.hotDeploy && !hotDeploy)
				stopHotDeployment();
			if (!this.hotDeploy && hotDeploy)
				startHotDeployment();
		}
		this.hotDeploy = hotDeploy;
	}
	
	public boolean isHotDeploy() {
		return hotDeploy;
	}
	
	public int getRetryInitInterval() {
		return retryInitInterval;
	}
	
	@MCAttribute
	public void setRetryInitInterval(int retryInitInterval) {
		this.retryInitInterval = retryInitInterval;
	}

	private ArrayList<Rule> getInactiveRules() {
		ArrayList<Rule> inactive = new ArrayList<Rule>();
		for (Rule rule : getRuleManager().getRules()) 
			if (!rule.isActive()) 
				inactive.add(rule);
		return inactive;
	}
	
}
