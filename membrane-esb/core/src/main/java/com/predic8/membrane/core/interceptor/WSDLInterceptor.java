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

package com.predic8.membrane.core.interceptor;

import static com.predic8.membrane.core.Constants.*;

import java.io.*;
import java.net.*;

import javax.xml.namespace.QName;
import javax.xml.stream.*;

import org.apache.commons.logging.*;

import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.*;
import com.predic8.membrane.core.transport.http.HttpClient;
import com.predic8.membrane.core.util.MessageUtil;
import com.predic8.membrane.core.ws.relocator.Relocator;

public class WSDLInterceptor extends RelocatingInterceptor {

	private static Log log = LogFactory.getLog(WSDLInterceptor.class.getName());

	private String registryWSDLRegisterURL;

	public WSDLInterceptor() {
		name = "WSDL Rewriting Interceptor";
		setFlow(Flow.RESPONSE);
	}

	@Override
	protected void rewrite(Exchange exc) throws Exception, IOException {

		log.debug("Changing endpoint address in WSDL");

		ByteArrayOutputStream stream = new ByteArrayOutputStream();

		Relocator relocator = new Relocator(new OutputStreamWriter(stream,
				getCharset(exc)), getLocationProtocol(), getLocationHost(exc),
				getLocationPort(exc));

		relocator.getRelocatingAttributes().put(
				new QName(WSDL_SOAP11_NS, "address"), "location");
		relocator.getRelocatingAttributes().put(
				new QName(WSDL_SOAP12_NS, "address"), "location");
		relocator.getRelocatingAttributes().put(
				new QName(WSDL_HTTP_NS, "address"), "location");
		relocator.getRelocatingAttributes().put(new QName(XSD_NS, "import"),
				"schemaLocation");
		relocator.getRelocatingAttributes().put(new QName(XSD_NS, "include"),
				"schemaLocation");

		relocator.relocate(new InputStreamReader(new ByteArrayInputStream(exc
				.getResponse().getBody().getContent()), getCharset(exc)));

		if (relocator.isWsdlFound()) {
			registerWSDL(exc);
		}
		exc.getResponse().setBodyContent(stream.toByteArray());
	}

	private void registerWSDL(Exchange exc) {
		if (registryWSDLRegisterURL == null)
			return;

		StringBuffer buf = new StringBuffer();
		buf.append(registryWSDLRegisterURL);
		buf.append("?wsdl=");

		try {
			buf.append(URLDecoder.decode(getWSDLURL(exc), "US-ASCII"));
		} catch (UnsupportedEncodingException e) {
			// ignored
		}

		callRegistry(buf.toString());

		log.debug(buf.toString());
	}

	private void callRegistry(String uri) {
		try {
			HttpClient client = new HttpClient();
			Response res = client.call(createExchange(uri));
			if (res.getStatusCode() != 200)
				log.warn(res);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Exchange createExchange(String uri) throws MalformedURLException {
		URL url = new URL(uri);
		Request req = MessageUtil.getGetRequest(getCompletePath(url));
		req.getHeader().setHost(url.getHost());
		Exchange exc = new Exchange();
		exc.setRequest(req);
		exc.getDestinations().add(uri);
		return exc;
	}

	private String getCompletePath(URL url) {
		if (url.getQuery() == null)
			return url.getPath();

		return url.getPath() + "?" + url.getQuery();
	}

	private String getWSDLURL(Exchange exc) {
		StringBuffer buf = new StringBuffer();
		buf.append(getLocationProtocol());
		buf.append("://");
		buf.append(getLocationHost(exc));
		if (getLocationPort(exc) != 80) {
			buf.append(":");
			buf.append(getLocationPort(exc));
		}
		buf.append("/");
		buf.append(exc.getRequest().getUri());
		return buf.toString();
	}

	public void setRegistryWSDLRegisterURL(String registryWSDLRegisterURL) {
		this.registryWSDLRegisterURL = registryWSDLRegisterURL;
	}

	public String getRegistryWSDLRegisterURL() {
		return registryWSDLRegisterURL;
	}

	@Override
	protected void writeInterceptor(XMLStreamWriter out)
			throws XMLStreamException {

		out.writeStartElement("wsdlRewriter");

		if (port != null)
			out.writeAttribute("port", port);
		if (host != null)
			out.writeAttribute("host", host);
		if (protocol != null)
			out.writeAttribute("protocol", protocol);
		if (registryWSDLRegisterURL != null)
			out.writeAttribute("registryWSDLRegisterURL",
					registryWSDLRegisterURL);

		out.writeEndElement();
	}

	@Override
	protected void parseAttributes(XMLStreamReader token) {

		port = token.getAttributeValue("", "port");
		host = token.getAttributeValue("", "host");
		protocol = token.getAttributeValue("", "protocol");
		registryWSDLRegisterURL = token.getAttributeValue("",
				"registryWSDLRegisterURL");
	}
}