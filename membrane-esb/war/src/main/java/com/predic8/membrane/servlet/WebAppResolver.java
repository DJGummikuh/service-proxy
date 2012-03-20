/* Copyright 2012 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.servlet;

import java.io.*;

import javax.servlet.ServletContext;

import org.apache.commons.logging.*;

import com.predic8.membrane.core.util.ResourceResolver;

public class WebAppResolver extends ResourceResolver {

	static private Log log = LogFactory.getLog(WebAppResolver.class.getName());

	private ServletContext ctx;

	@Override
	protected InputStream resolveFile(String uri, boolean useMembraneHome)
			throws FileNotFoundException {
		log.debug("loading resource from: " + uri);
		return ctx.getResourceAsStream(uri);
	}

	public ServletContext getCtx() {
		return ctx;
	}

	public void setCtx(ServletContext ctx) {
		this.ctx = ctx;
	}

}