/*
 * aoweb-framework - Legacy servlet-based web framework, superfast and capable but tedious to use.
 * Copyright (C) 2000-2013, 2015, 2016, 2019, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoweb-framework.
 *
 * aoweb-framework is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoweb-framework is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoweb-framework.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.website.framework;

import com.aoindustries.collections.SortedArrayList;
import com.aoindustries.encoding.Doctype;
import com.aoindustries.encoding.Serialization;
import com.aoindustries.encoding.servlet.DoctypeEE;
import com.aoindustries.encoding.servlet.EncodingContextEE;
import com.aoindustries.encoding.servlet.SerializationEE;
import com.aoindustries.html.Html;
import com.aoindustries.lang.Strings;
import com.aoindustries.net.EmptyURIParameters;
import com.aoindustries.net.URIParameters;
import com.aoindustries.servlet.ServletRequestParameters;
import com.aoindustries.servlet.ServletUtil;
import com.aoindustries.servlet.http.HttpServletUtil;
import com.aoindustries.web.resources.registry.Registry;
import com.aoindustries.web.resources.servlet.PageServlet;
import com.aoindustries.web.resources.servlet.RegistryEE;
import java.io.CharArrayWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import javax.security.auth.login.LoginException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The main web page provides the overall layout of the site.  The rest of
 * the site overrides methods of this class, but cannot override the
 * <code>reportingDoGet</code>, <code>reportingDoPost</code>, or
 * <code>reportingGetLastModified</code> methods.
 *
 * @author  AO Industries, Inc.
 */
abstract public class WebPage extends ErrorReportingServlet {

	/**
	 * The name of the search form during per-page searches.
	 */
	public static final String SEARCH_TWO = "search_two";

	private static final long serialVersionUID = 1L;

	/**
	 * An empty array of <code>WebPage</code> objects to be used in returning no web pages.
	 */
	protected static final WebPage[] emptyWebPageArray = new WebPage[0];

	/**
	 * Caches instances of <code>WebPage</code> for reuse.  The storage is a
	 * <code>HashMap</code> of <code>ArrayList</code>s, keyed on class.
	 *
	 * @see  #getWebPage(ServletContext,Class,WebSiteRequest)
	 * @see  #getWebPage(ServletContext,Class,Object)
	 */
	private static final Map<Class<?>,List<WebPage>> webPageCache = new HashMap<>();

	/**
	 * Stores a cache of the list of child pages, once created.
	 *
	 * @see  #getCachedChildren(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 */
	private WebPage[] cachedChildren;

	/**
	 * The last modified time of the content in the search index or <code>-1</code> if not indexed.
	 */
	private long searchLastModified=-1;

	/**
	 * The number of bytes in the document at last index time, used to properly weight the search results.
	 */
	private int searchByteCount;

	/**
	 * The words that are indexed, sorted.
	 */
	private final List<String> searchWords=new SortedArrayList<>();

	/**
	 * The number times each word appears in the document.
	 */
	private final List<int[]> searchCounts=new ArrayList<>();

	// TODO: Use a full HTML parser for extraction, JTidy or newer alternative
	public static final Pattern reHTMLPattern = Pattern.compile("<[^>]*>");
	//private static Pattern reWordPattern = Pattern.compile("(\\w*)");

	/**
	 * Configures the {@linkplain com.aoindustries.web.resources.servlet.RegistryEE.Page page-scope web resources} that this page uses.
	 * <p>
	 * Implementers should call <code>super.configureResources(…)</code> as a matter of convention, despite this default implementation doing nothing.
	 * </p>
	 */
	@SuppressWarnings("NoopMethodInAbstractClass")
	public void configureResources(ServletContext servletContext, WebSiteRequest req, HttpServletResponse resp, WebPageLayout layout, Registry pageRegistry) {
		// Do nothing
	}

	private void addSearchWords(String words, int weight) {
		// Remove HTML
		// if(words.contains("<PRE>")) System.err.println("BEFORE: " + words);
		words = reHTMLPattern.matcher(words).replaceAll(" ");
		// if(words.contains("<PRE>")) System.err.println("AFTER.: " + words);

		// Iterate through all the words in the content
		StringTokenizer st=new StringTokenizer(words, " ");
		while(st.hasMoreTokens()) {
			String word=st.nextToken().toLowerCase(); //reWordPattern.getMatch(st.nextToken()).toString(1);

			// Find the index of the word
			int index=searchWords.indexOf(word);
			if(index==-1) {
				// Add to the word list
				searchWords.add(word);
				index=searchWords.indexOf(word);
				searchCounts.add(index, new int[] {weight});
			} else {
				// Increment the existing count
				searchCounts.get(index)[0]+=weight;
			}
		}
	}

	/**
	 * Determines if the provided user can access the page.  Defaults
	 * to inheriting the behavior of the parent page.
	 */
	public boolean canAccess(WebSiteUser user) throws IOException, SQLException {
		return getParent().canAccess(user);
	}

	/**
	 * Prints the form that is used to login.
	 */
	public void printLoginForm(WebPage page, LoginException loginException, WebSiteRequest req, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		getParent().printLoginForm(page, loginException, req, resp);
	}

	/**
	 * Prints the unauthorized page message.
	 */
	public void printUnauthorizedPage(WebPage page, WebSiteRequest req, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		getParent().printUnauthorizedPage(page, req, resp);
	}

	// <editor-fold defaultstate="collapsed" desc="getLastModified() Requests">
	
	/**
	 * The main entry point for <code>getLastModified()</code> requests.
	 * Prepares the request and performs initial actions:
	 * <ol>
	 *   <li>Wraps the request in {@link WebSiteRequest}
	 *       via {@link #getWebSiteRequest(javax.servlet.http.HttpServletRequest)}.</li>
	 *   <li>Resolves the current instance of {@link WebPage}
	 *       via {@link #getWebPage(java.lang.Class, com.aoindustries.website.framework.WebSiteRequest)}.</li>
	 *   <li>Handles any login request (parameter {@link WebSiteRequest#LOGIN_REQUESTED}="true")
	 *       by returning {@code -1} for unknown.</li>
	 *   <li>Resolves the current {@link WebSiteUser}
	 *       via {@link WebSiteRequest#getWebSiteUser(javax.servlet.http.HttpServletResponse)} (if any).
	 *       When {@linkplain LoginException login required and failed},
	 *       returns {@code -1} for unknown.</li>
	 *   <li>Ensures the {@linkplain WebPage#canAccess(com.aoindustries.website.framework.WebSiteUser) user can access the page},
	 *       returns {@code -1} for unknown
	 *       when not authorized.</li>
	 *   <li>If {@linkplain #getRedirectURL(com.aoindustries.website.framework.WebSiteRequest) is a redirect},
	 *       returns {@code -1} for unknown.</li>
	 *   <li>Finally, dispatches the request to {@link #getLastModified(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}.</li>
	 * </ol>
	 *
	 * @see #getLastModified(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 */
	@Override
	final protected long reportingGetLastModified(HttpServletRequest httpReq, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		WebSiteRequest req = getWebSiteRequest(httpReq);
		WebPage page = getWebPage(getClass(), req);

		if(Boolean.parseBoolean(req.getParameter(WebSiteRequest.LOGIN_REQUESTED))) {
			return -1;
		}
		WebSiteUser user;
		try {
			user = req.getWebSiteUser(null);
		} catch(LoginException err) {
			return -1;
		}
		if(!page.canAccess(user)) return -1;

		// If redirected
		if(page.getRedirectURL(req) != null) return -1;

		return page.getLastModified(req, resp);
	}

	/**
	 * The <code>getLastModified</code> defaults to {@code -1}.
	 */
	public long getLastModified(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		return -1;
	}

	/**
	 * Gets the last modified time of the java class file.  If the class file is
	 * unavailable, it defaults to the time the servlets were loaded.
	 *
	 * @see  ErrorReportingServlet#getUptime()
	 */
	final protected long getClassLastModified() throws IOException, SQLException {
		String dir = getServletContext().getRealPath("/WEB-INF/classes");
		if(dir != null && dir.length() > 0) {
			// Try to get from the class file
			long lastMod = new File(dir, getClass().getName().replace('.', File.separatorChar) + ".class").lastModified();
			if(lastMod != 0 && lastMod != -1) return lastMod;
		}
		return getUptime();
	}

	/**
	 * Gets the most recent last modified time of this page and its immediate children.
	 */
	public long getWebPageAndChildrenLastModified(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		WebPage[] children = getCachedChildren(req, resp);
		int len = children.length;
		long mostRecent = getClassLastModified();
		if(mostRecent==-1) return -1;
		for (int c = 0; c < len; c++) {
			long time = children[c].getLastModified(req, resp);
			if(time==-1) return -1;
			if (time > mostRecent) mostRecent = time;
		}
		return mostRecent;
	}

	/**
	 * Recursively gets the most recent modification time.
	 */
	final public long getLastModifiedRecursive(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		long time = getLastModified(req, resp);
		WebPage[] children = getCachedChildren(req, resp);
		int len = children.length;
		for(int c=0; c<len; c++) {
			long time2 = children[c].getLastModifiedRecursive(req, resp);
			if(time2 > time) time = time2;
		}
		return time;
	}

	/**
	 * Recursively gets the most recent modification time of a file or directory.
	 */
	public static long getLastModifiedRecursive(File file) {
		long time=file.lastModified();
		if(file.isDirectory()) {
			String[] list=file.list();
			if(list != null) {
				int len = list.length;
				for(int c=0; c<len; c++) {
					long time2 = getLastModifiedRecursive(new File(file, list[c]));
					if (time2 > time) time = time2;
				}
			}
		}
		return time;
	}

	/**
	 * Gets the last modified time for search indexing.  The index will be recreated if
	 * the search last modified time is changed.  If this method returns <code>-1</code>,
	 * no search index is built.  This defaults to be a call to <code>getLastModified</code>
	 * with null {@link WebSiteRequest} and {@link HttpServletResponse}.
	 */
	public long getSearchLastModified() throws IOException, SQLException {
		return getLastModified(null, null);
	}

	// </editor-fold>

	/**
	 * Gets the {@link Serialization} to use for this page.
	 *
	 * @param req  {@code null} during search
	 *
	 * @see Serialization#DEFAULT
	 * @see SerializationEE#getDefault(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest)
	 */
	protected Serialization getSerialization(WebSiteRequest req) {
		return (req == null) ? Serialization.DEFAULT : SerializationEE.getDefault(getServletContext(), req);
	}

	/**
	 * Gets the {@link Doctype} to use for this page.
	 *
	 * @param req  {@code null} during search
	 *
	 * @see Doctype#DEFAULT
	 */
	protected Doctype getDoctype(WebSiteRequest req) {
		return Doctype.DEFAULT;
	}

	/**
	 * Prepares for output and returns the {@link Html}.
	 * <ol>
	 *   <li>{@linkplain ServletResponse#resetBuffer() clears the output buffer}.</li>
	 *   <li>Sets the {@linkplain ServletResponse#setContentType(java.lang.String) response content type}.</li>
	 *   <li>Sets the {@linkplain ServletResponse#setCharacterEncoding(java.lang.String) response character encoding}
	 *       to {@linkplain Html#ENCODING the default <code>UTF-8</code>}.</li>
	 *   <li>Sets any {@linkplain #getAdditionalHeaders(com.aoindustries.website.framework.WebSiteRequest) additional headers}.</li>
	 * </ol>
	 * <p>
	 * Both the {@link Serialization} and {@link Doctype} may have been set
	 * on the request, and these must be considered in the content type.
	 * </p>
	 *
	 * @see SerializationEE#get(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest)
	 * @see DoctypeEE#get(javax.servlet.ServletContext, javax.servlet.ServletRequest)
	 * @see #getAdditionalHeaders(com.aoindustries.website.framework.WebSiteRequest)
	 */
	protected Html getHTML(WebSiteRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// Clear the output buffer
		resp.resetBuffer();
		// Set the content type
		Serialization serialization = getSerialization(req);
		ServletUtil.setContentType(
			resp,
			serialization.getContentType(),
			Html.ENCODING
		);
		// Set additional headers
		String[] headers = getAdditionalHeaders(req);
		if(headers != null) {
			int len = headers.length;
			for(int c = 0; c < len; c += 2) {
				resp.setHeader(headers[c], headers[c + 1]);
			}
		}
		Doctype doctype = getDoctype(req);
		return new Html(
			new EncodingContextEE(
				getServletContext(),
				req,
				resp
			) {
				@Override
				public Serialization getSerialization() {
					return serialization;
				}
				@Override
				public Doctype getDoctype() {
					return doctype;
				}
			},
			resp.getWriter()
		);
	}

	/**
	 * Prepares for output and returns the {@link OutputStream}.
	 * <ol>
	 *   <li>{@linkplain ServletResponse#resetBuffer() clears the output buffer}.</li>
	 *   <li>Sets the {@linkplain ServletResponse#setContentType(java.lang.String) response content type}.</li>
	 *   <li>Sets the {@linkplain ServletResponse#setCharacterEncoding(java.lang.String) response character encoding}
	 *       to {@linkplain Html#ENCODING the default <code>UTF-8</code>}.</li>
	 *   <li>Sets any {@linkplain #getAdditionalHeaders(com.aoindustries.website.framework.WebSiteRequest) additional headers}.</li>
	 * </ol>
	 * <p>
	 * Both the {@link Serialization} and {@link Doctype} may have been set
	 * on the request, and these must be considered in the content type.
	 * </p>
	 *
	 * @see SerializationEE#get(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest)
	 * @see DoctypeEE#get(javax.servlet.ServletContext, javax.servlet.ServletRequest)
	 * @see #getAdditionalHeaders(com.aoindustries.website.framework.WebSiteRequest)
	 */
	protected OutputStream getHTMLOutputStream(WebSiteRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// Clear the output buffer
		resp.resetBuffer();
		// Set the content type
		ServletUtil.setContentType(
			resp,
			SerializationEE.get(getServletContext(), req).getContentType(),
			Html.ENCODING
		);
		// Set additional headers
		String[] headers = getAdditionalHeaders(req);
		if(headers != null) {
			int len = headers.length;
			for(int c = 0; c < len; c += 2) {
				resp.setHeader(headers[c], headers[c + 1]);
			}
		}
		return resp.getOutputStream();
	}

	/**
	 * Sets the page-scope registry.
	 * <p>
	 * TODO: Just subclass {@link PageServlet} once we no longer extend
	 * {@link ErrorReportingServlet}.
	 * </p>
	 *
	 * @see  PageServlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)
	 */
	@Override
	public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
		Registry oldPageRegistry = RegistryEE.Page.get(request);
		if(oldPageRegistry == null) {
			// Create a new page-scope registry
			RegistryEE.Page.set(request, new Registry());
		}
		try {
			super.service(request, response);
		} finally {
			if(oldPageRegistry == null) {
				RegistryEE.Page.set(request, null);
			}
		}
	}

	// <editor-fold defaultstate="collapsed" desc="GET Requests">
	/**
	 * The main entry point for <code>GET</code> requests.
	 * Prepares the request and performs initial actions:
	 * <ol>
	 *   <li>Wraps the request in {@link WebSiteRequest}
	 *       via {@link #getWebSiteRequest(javax.servlet.http.HttpServletRequest)}.</li>
	 *   <li>Resolves the current instance of {@link WebPage}
	 *       via {@link #getWebPage(java.lang.Class, com.aoindustries.website.framework.WebSiteRequest)}.</li>
	 *   <li>Handles any logout request (parameter {@link WebSiteRequest#LOGOUT_REQUESTED}="true")
	 *       via {@link WebSiteRequest#logout()}.</li>
	 *   <li>Handles any login request (parameter {@link WebSiteRequest#LOGIN_REQUESTED}="true")
	 *       by invoking {@link WebPage#printLoginForm(com.aoindustries.website.framework.WebPage, com.aoindustries.security.LoginException, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}
	 *       and stops here.</li>
	 *   <li>Resolves the current {@link WebSiteUser}
	 *       via {@link WebSiteRequest#getWebSiteUser(javax.servlet.http.HttpServletResponse)} (if any).
	 *       When {@linkplain LoginException login required and failed},
	 *       invokes {@link WebPage#printLoginForm(com.aoindustries.website.framework.WebPage, com.aoindustries.security.LoginException, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}
	 *       and stops here.</li>
	 *   <li>Ensures the {@linkplain WebPage#canAccess(com.aoindustries.website.framework.WebSiteUser) user can access the page},
	 *       invokes {@link WebPage#printUnauthorizedPage(com.aoindustries.website.framework.WebPage, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}
	 *       when not authorized and stops here.</li>
	 *   <li>If {@linkplain #getRedirectURL(com.aoindustries.website.framework.WebSiteRequest) is a redirect},
	 *       {@linkplain HttpServletUtil#sendRedirect(int, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, com.aoindustries.net.URIParameters, boolean, boolean) sends the redirect}
	 *       of the {@linkplain #getRedirectType() correct type} and stops here.</li>
	 *   <li>Finally, dispatches the request to {@link #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}.</li>
	 * </ol>
	 *
	 * @see #doGet(WebSiteRequest,HttpServletResponse)
	 */
	@Override
	final protected void reportingDoGet(HttpServletRequest httpReq, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		WebSiteRequest req = getWebSiteRequest(httpReq);
		WebPage page = getWebPage(getClass(), req);
		// Logout when requested
		boolean isLogout = Boolean.parseBoolean(req.getParameter(WebSiteRequest.LOGOUT_REQUESTED));
		if(isLogout) req.logout(resp);

		if(Boolean.parseBoolean(req.getParameter(WebSiteRequest.LOGIN_REQUESTED))) {
			// TODO: robots header on WebSiteRequest.LOGIN_REQUESTED
			page.printLoginForm(page, new LoginException("Please Login"), req, resp);
			return;
		}
		WebSiteUser user;
		try {
			user = req.getWebSiteUser(resp);
		} catch(LoginException err) {
			page.printLoginForm(page, err, req, resp);
			return;
		}
		if(!page.canAccess(user)) {
			page.printUnauthorizedPage(page, req, resp);
			return;
		}
		String redirect = page.getRedirectURL(req);
		if(redirect != null) {
			HttpServletUtil.sendRedirect(
				page.getRedirectType(),
				req,
				resp,
				redirect,
				EmptyURIParameters.getInstance(),
				true,
				false
			);
			return;
		}
		page.doGet(req, resp);
	}

	/**
	 * Prepares the request then invokes {@link #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)}.
	 * To not have these steps automatically applied, override this method.
	 * By the time this method is called, security checks, authentication, and redirects have been done.
	 * <ol>
	 *   <li>Sets the {@linkplain Serialization serialization}.</li>
	 *   <li>Sets the {@linkplain Doctype DOCTYPE}.</li>
	 *   <li>Gets the {@linkplain #getHTML(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse) response writer}.</li>
	 *   <li>Invokes {@link #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)}.</li>
	 * </ol>
	 *
	 * @see #reportingDoGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 * @see #getSerialization(com.aoindustries.website.framework.WebSiteRequest)
	 * @see #getDoctype(com.aoindustries.website.framework.WebSiteRequest)
	 * @see #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)
	 */
	public void doGet(WebSiteRequest req, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		Serialization serialization = getSerialization(req);
		Serialization oldSerialization = SerializationEE.replace(req, serialization);
		try {
			Doctype oldDoctype = DoctypeEE.replace(req, getDoctype(req));
			try {
				doGet(req, resp, getHTML(req, resp));
			} finally {
				DoctypeEE.set(req, oldDoctype);
			}
		} finally {
			SerializationEE.set(req, oldSerialization);
		}
	}

	/**
	 * The layout is automatically applied to the page, then {@link #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, com.aoindustries.website.framework.WebPageLayout)}
	 * is called.  To not have the layout automatically applied, override this method.
	 * By the time this method is called, security checks, authentication, redirects, doctype, and serialization have been done.
	 *
	 * @param  req   the {@link WebSiteRequest} for this request, or {@code null} when searching
	 * @param  resp  the {@link HttpServletResponse} for this request, or {@code null} when searching
	 * @param  html  the {@link Html} to send output to
	 *
	 * @see #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 * @see #getWebPageLayout(com.aoindustries.website.framework.WebSiteRequest)
	 * @see WebPageLayout#startHTML(com.aoindustries.website.framework.WebPage, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, java.lang.String)
	 * @see #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, com.aoindustries.website.framework.WebPageLayout)
	 * @see WebPageLayout#endHTML(com.aoindustries.website.framework.WebPage, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)
	 */
	// TODO: We could have a NullHtmlWriter that does not write any HTML tags or attributes, but just the text body.
	//       Then there could be a search-specific request object, instead of null, which is used during searches.
	//       This NullHtmlWriter could wrap something that skips HTML tags (in case of direct writes - is possible through Html abstraction)
	//       Finally, this could all go to a writer that builds word indexes on-the-fly.
	//       This could support deferred attributes (at least in a servlet context), to avoid processing attributes that will be discarded
	public void doGet(
		WebSiteRequest req,
		HttpServletResponse resp,
		Html html
	) throws ServletException, IOException, SQLException {
		WebPageLayout layout = getWebPageLayout(req);
		layout.startHTML(this, req, resp, html, null);
		doGet(req, resp, html, layout);
		layout.endHTML(this, req, resp, html);
	}

	/**
	 * By default, GET provides no content.
	 *
	 * @param  req     the {@link WebSiteRequest} for this request, or {@code null} when searching
	 * @param  resp    the {@link HttpServletResponse} for this request, or {@code null} when searching
	 * @param  html    the {@link Html} to send output to
	 * @param  layout  the {@link WebPageLayout} that has been applied
	 *
	 * @see #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)
	 */
	@SuppressWarnings("NoopMethodInAbstractClass")
	public void doGet(
		WebSiteRequest req,
		HttpServletResponse resp,
		Html html,
		WebPageLayout layout
	) throws ServletException, IOException, SQLException {
	}

	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="POST Requests">

	/**
	 * The main entry point for <code>GET</code> requests.
	 * Prepares the request and performs initial actions:
	 * <ol>
	 *   <li>Wraps the request in {@link WebSiteRequest}
	 *       via {@link #getWebSiteRequest(javax.servlet.http.HttpServletRequest)}.</li>
	 *   <li>Resolves the current instance of {@link WebPage}
	 *       via {@link #getWebPage(java.lang.Class, com.aoindustries.website.framework.WebSiteRequest)}.</li>
	 *   <li>Handles any logout request (parameter {@link WebSiteRequest#LOGOUT_REQUESTED}="true")
	 *       via {@link WebSiteRequest#logout()}.</li>
	 *   <li>Handles any login request (parameter {@link WebSiteRequest#LOGIN_REQUESTED}="true")
	 *       by invoking {@link WebPage#printLoginForm(com.aoindustries.website.framework.WebPage, com.aoindustries.security.LoginException, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}
	 *       and stops here.</li>
	 *   <li>Resolves the current {@link WebSiteUser}
	 *       via {@link WebSiteRequest#getWebSiteUser(javax.servlet.http.HttpServletResponse)} (if any).
	 *       When {@linkplain LoginException login required and failed},
	 *       invokes {@link WebPage#printLoginForm(com.aoindustries.website.framework.WebPage, com.aoindustries.security.LoginException, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}
	 *       and stops here.</li>
	 *   <li>Ensures the {@linkplain WebPage#canAccess(com.aoindustries.website.framework.WebSiteUser) user can access the page},
	 *       invokes {@link WebPage#printUnauthorizedPage(com.aoindustries.website.framework.WebPage, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}
	 *       when not authorized and stops here.</li>
	 *   <li>If {@linkplain #getRedirectURL(com.aoindustries.website.framework.WebSiteRequest) is a redirect},
	 *       {@linkplain HttpServletUtil#sendRedirect(int, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, com.aoindustries.net.URIParameters, boolean, boolean) sends the redirect}
	 *       of the {@linkplain #getRedirectType() correct type} and stops here.</li>
	 *   <li>Avoid unexpected POST action after a (re)login: If has parameter {@link WebSiteRequest#LOGIN_REQUESTED}="true"
	 *       or both {@link WebSiteRequest#LOGIN_USERNAME} and {@link WebSiteRequest#LOGIN_PASSWORD} parameters, dispatch to
	 *       {@link #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}
	 *       and stop here.</li>
	 *   <li>Finally, dispatches the request to {@link #doPostWithSearch(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}.</li>
	 * </ol>
	 *
	 * @see #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 * @see #doPostWithSearch(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 */
	@Override
	final protected void reportingDoPost(HttpServletRequest httpReq, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		WebSiteRequest req = getWebSiteRequest(httpReq);
		WebPage page = getWebPage(getClass(), req);
		// Logout when requested
		boolean isLogout = Boolean.parseBoolean(req.getParameter(WebSiteRequest.LOGOUT_REQUESTED));
		if(isLogout) req.logout(resp);

		if(Boolean.parseBoolean(req.getParameter(WebSiteRequest.LOGIN_REQUESTED))) {
			page.printLoginForm(page, new LoginException("Please Login"), req, resp);
			return;
		}
		WebSiteUser user;
		try {
			user = req.getWebSiteUser(resp);
		} catch(LoginException err) {
			page.printLoginForm(page, err, req, resp);
			return;
		}
		if(!page.canAccess(user)) {
			page.printUnauthorizedPage(page, req, resp);
			return;
		}
		String redirect = page.getRedirectURL(req);
		if(redirect != null) {
			HttpServletUtil.sendRedirect(
				page.getRedirectType(),
				req,
				resp,
				redirect,
				EmptyURIParameters.getInstance(),
				true,
				false
			);
			return;
		}
		if(
			isLogout
			|| (
				req.getParameter(WebSiteRequest.LOGIN_USERNAME) != null
				&& req.getParameter(WebSiteRequest.LOGIN_PASSWORD) != null
			)
		) {
			page.doGet(req, resp);
		} else {
			page.doPostWithSearch(req, resp);
		}
	}

	/**
	 * Handles any search posts, sends everything else on to {@link #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}.
	 * The search assumes the search parameters of {@link WebSiteRequest#SEARCH_QUERY} and {@link WebSiteRequest#SEARCH_TARGET}.  Both
	 * these values must be present for a search to be performed.  Search target may be either {@link WebSiteRequest#SEARCH_THIS_AREA}
	 * or {@link WebSiteRequest#SEARCH_ENTIRE_SITE}, defaulting to {@link WebSiteRequest#SEARCH_THIS_AREA} for any other value.
	 *
	 * @see #reportingDoPost(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 * @see #doPost(WebSiteRequest,HttpServletResponse)
	 */
	protected void doPostWithSearch(WebSiteRequest req, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		String query = req.getParameter(WebSiteRequest.SEARCH_QUERY);
		String searchTarget = req.getParameter(WebSiteRequest.SEARCH_TARGET);
		if(query != null && searchTarget != null) {
			Serialization serialization = getSerialization(req);
			Serialization oldSerialization = SerializationEE.replace(req, serialization);
			try {
				Doctype oldDoctype = DoctypeEE.replace(req, getDoctype(req));
				try {
					Html html = getHTML(req, resp);
					WebPageLayout layout = getWebPageLayout(req);
					layout.startHTML(this, req, resp, html, "document.forms." + SEARCH_TWO + "." + WebSiteRequest.SEARCH_QUERY + ".select(); document.forms." + SEARCH_TWO + "." + WebSiteRequest.SEARCH_QUERY + ".focus();");

					boolean entire_site = searchTarget.equals(WebSiteRequest.SEARCH_ENTIRE_SITE);
					WebPage target = entire_site ? getRootPage() : this;

					// If the target contains no pages, use its parent
					if(target.getCachedChildren(req, resp).length == 0) target=target.getParent();

					// Get the list of words to search for
					String[] words=Strings.split(query.replace('.', ' '));

					List<SearchResult> results=new ArrayList<>();
					if(words.length>0) {
						// Perform the search
						target.search(words, req, resp, results, new CharArrayWriter(), new HashSet<>());
						Collections.sort(results);
						//Strings.sortObjectsAndFloatDescending(results, 1, 5);
					}

					layout.printSearchOutput(this, html, req, resp, query, entire_site, results, words);

					layout.endHTML(this, req, resp, html);
				} finally {
					DoctypeEE.set(req, oldDoctype);
				}
			} finally {
				SerializationEE.set(req, oldSerialization);
			}
		} else {
			doPost(req, resp);
		}
	}

	/**
	 * Prepares the request then invokes {@link #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)}.
	 * To not have these steps automatically applied, override this method.
	 * By the time this method is called, security checks, authentication, and redirects have been done.
	 * <ol>
	 *   <li>Sets the {@linkplain Serialization serialization}.</li>
	 *   <li>Sets the {@linkplain Doctype DOCTYPE}.</li>
	 *   <li>Gets the {@linkplain #getHTML(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse) response writer}.</li>
	 *   <li>Invokes {@link #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)}.</li>
	 * </ol>
	 *
	 * @see #doPostWithSearch(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 * @see #getSerialization(com.aoindustries.website.framework.WebSiteRequest)
	 * @see #getDoctype(com.aoindustries.website.framework.WebSiteRequest)
	 * @see #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)
	 */
	public void doPost(WebSiteRequest req, HttpServletResponse resp) throws ServletException, IOException, SQLException {
		Serialization serialization = getSerialization(req);
		Serialization oldSerialization = SerializationEE.replace(req, serialization);
		try {
			Doctype oldDoctype = DoctypeEE.replace(req, getDoctype(req));
			try {
				doPost(req, resp, getHTML(req, resp));
			} finally {
				DoctypeEE.set(req, oldDoctype);
			}
		} finally {
			SerializationEE.set(req, oldSerialization);
		}
	}

	/**
	 * The layout is automatically applied to the page, then {@link #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, com.aoindustries.website.framework.WebPageLayout)}
	 * is called.  To not have the layout automatically applied, override this method.
	 * By the time this method is called, security checks, authentication, redirects, doctype, and serialization have been done.
	 *
	 * @param  req   the {@link WebSiteRequest} for this request, or {@code null} when searching
	 * @param  resp  the {@link HttpServletResponse} for this request, or {@code null} when searching
	 * @param  html  the {@link Html} to send output to
	 *
	 * @see #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 * @see #getWebPageLayout(com.aoindustries.website.framework.WebSiteRequest)
	 * @see WebPageLayout#startHTML(com.aoindustries.website.framework.WebPage, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, java.lang.String)
	 * @see #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, com.aoindustries.website.framework.WebPageLayout)
	 * @see WebPageLayout#endHTML(com.aoindustries.website.framework.WebPage, com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)
	 */
	public void doPost(
		WebSiteRequest req,
		HttpServletResponse resp,
		Html html
	) throws ServletException, IOException, SQLException {
		WebPageLayout layout = getWebPageLayout(req);
		layout.startHTML(this, req, resp, html, null);
		doPost(req, resp, html, layout);
		layout.endHTML(this, req, resp, html);
	}

	/**
	 * By default, a POST request just calls {@link #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, com.aoindustries.website.framework.WebPageLayout)}.
	 *
	 * @param  req     the current {@link WebSiteRequest}
	 * @param  resp    the {@link HttpServletResponse} for this request
	 * @param  html    the {@link Html} to send output to
	 * @param  layout  the {@link WebPageLayout} that has been applied
	 *
	 * @see #doPost(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html)
	 * @see #doGet(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.html.Html, com.aoindustries.website.framework.WebPageLayout)
	 */
	public void doPost(
		WebSiteRequest req,
		HttpServletResponse resp,
		Html html,
		WebPageLayout layout
	) throws ServletException, IOException, SQLException {
		doGet(req, resp, html, layout);
	}

	// </editor-fold>

	/**
	 * Determines if this page equals another page.
	 *
	 * @see  #equals(WebPage)
	 */
	@Override
	final public boolean equals(Object O) {
		return
			(O instanceof WebPage)
			&& equals((WebPage)O)
		;
	}

	/**
	 * Determines if this page equals another page.  By default, two pages
	 * of the same classname are considered equal.
	 *
	 * @see  #hashCode
	 */
	public boolean equals(WebPage other) {
		return other.getClass().getName().equals(getClass().getName());
	}

	/**
	 * The default hashcode for a page is the hashcode of its
	 * classname.
	 *
	 * @see  #equals(WebPage)
	 */
	@Override
	public int hashCode() {
		return getClass().getName().hashCode();
	}

	/**
	 * Gets additional headers for this page.  The format must be in a String[] of name/value pairs, two elements each, name and then value.
	 */
	// TODO: Return a Map<String,? extend Iterable<String>> ?
	public String[] getAdditionalHeaders(WebSiteRequest req) {
		return null;
	}

	/**
	 * Gets the author of this page.  By default, the author of the parent page is used.
	 */
	public String getAuthor() throws IOException, SQLException {
		return getParent().getAuthor();
	}

	/**
	 * Gets the URL for the author of this page.  By default, the URL of the author of the parent page is used.
	 */
	public String getAuthorHref(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		return getParent().getAuthorHref(req, resp);
	}

	/**
	 * Gets the preferred width of this content in pixels or <code>-1</code> for no preference.
	 * It is up to the <code>WebPageLayout</code> to make use of this value.  The preferred width
	 * defaults to the preferred width of the parent page.
	 *
	 * @see  WebPageLayout
	 */
	public int getPreferredContentWidth(WebSiteRequest req) throws IOException, SQLException {
		return getParent().getPreferredContentWidth(req);
	}

	/**
	 * Gets the vertical alignment of the content area.  Defaults to <code>"top"</code>.
	 * It is up to the <code>WebPageLayout</code> to make use of this value.
	 *
	 * @see  WebPageLayout
	 */
	public String getContentVAlign(WebSiteRequest req) {
		return "top";
	}

	/**
	 * Gets the description of this page.  By default, the description of the parent page is used.
	 * May not contain HTML.
	 */
	public String getDescription() throws IOException, SQLException {
		return getParent().getDescription();
	}

	/**
	 * Gets the root page in the web page hierarchy.  The root page has no parent.
	 */
	public final WebPage getRootPage() throws IOException, SQLException {
		WebPage page = this;
		WebPage parent;
		while ((parent = page.getParent()) != null) {
			page = parent;
		}
		return page;
	}

	/**
	 * Gets the JavaScript's that should have script src= tags generated, urls relative to top of context <code>path/to/javascript.js</code>.
	 *
	 * @param  req  the current <code>WebSiteRequest</code>
	 *
	 * @return  a <code>String[]</code> for multiple includes,
	 *          a <code>String</code> for one,
	 *          or <code>null</code> for none
	 */
	public Object getJavaScriptSrc(WebSiteRequest req) throws IOException, SQLException {
		return null;
	}

	/**
	 * Gets the keywords for this page.  By default, the keywords of the parent page are used.
	 */
	// TODO: Is it correct to use keywords of parent?
	public String getKeywords() throws IOException, SQLException {
		return getParent().getKeywords();
	}

	/**
	 * Gets the text for the navigation image to use to represent this page.  Defaults to <code>getShortTitle</code>.
	 *
	 * @return  the alt text of the navigation image
	 *
	 * @see #getShortTitle()
	 * @see #getNavImageSuffix(com.aoindustries.website.framework.WebSiteRequest)
	 * @see #getNavImageURL(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.net.URIParameters)
	 */
	public String getNavImageAlt(WebSiteRequest req) throws IOException, SQLException {
		return getShortTitle();
	}

	/**
	 * Gets the text that will be placed in to the right of the navigation image.  If the
	 * image is not large enough to hold both <code>getNavImageAlt</code> and <code>getNavImageSuffix</code>,
	 * the beginning is truncated and <code>...</code> appended so that both fit the image.
	 *
	 * @see #getNavImageAlt(com.aoindustries.website.framework.WebSiteRequest)
	 * @see #getNavImageURL(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse, com.aoindustries.net.URIParameters)
	 */
	public String getNavImageSuffix(WebSiteRequest req) throws IOException, SQLException {
		return null;
	}

	/**
	 * Gets the URL associated with a nav image, fully encoded.
	 *
	 * @param  params  Only adds a value when the name has not already been added to the URL.
	 *                 This does not support multiple values, only the first is used.
	 *
	 * @see #getNavImageAlt(com.aoindustries.website.framework.WebSiteRequest)
	 * @see #getNavImageSuffix(com.aoindustries.website.framework.WebSiteRequest)
	 */
	public String getNavImageURL(WebSiteRequest req, HttpServletResponse resp, URIParameters params) throws IOException, SQLException {
		return req.getEncodedURL(this, params, resp);
	}

	/**
	 * Gets the index of this page in the parents list of children pages.
	 */
	final public int getPageIndexInParent(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		WebPage[] siblings = getParent().getCachedChildren(req, resp);
		int len=siblings.length;
		for(int c = 0; c < len; c++) {
			if(siblings[c].equals(this)) return c;
		}
		throw new RuntimeException("Unable to find page index in parent.");
	}

	/**
	 * Gets the <code>WebPage</code> that follows this one in the parents
	 * list of pages.
	 *
	 * @return  the <code>WebPage</code> or <code>null</code> if not found
	 */
	final public WebPage getNextPage(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		WebPage parent=getParent();
		if (parent!=null) {
			WebPage[] siblings = parent.getCachedChildren(req, resp);
			int len=siblings.length;
			for(int c=0; c<len; c++) {
				if(siblings[c].getClass() == getClass()) {
					if (c < (len - 1)) return siblings[c + 1];
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Gets the <code>WebPage</code> that proceeds this one in the parents
	 * list of pages.
	 *
	 * @return  the <code>WebPage</code> or <code>null</code> if not found
	 */
	final public WebPage getPreviousPage(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		WebPage parent = getParent();
		if (parent != null) {
			WebPage[] siblings = parent.getCachedChildren(req, resp);
			int len = siblings.length;
			for (int c = 0; c < len; c++) {
				if (siblings[c].getClass() == getClass()) {
					if (c > 0) return siblings[c - 1];
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Gets the JavaScript that should be executed with the onload event of the body tag
	 *
	 * @param  req  the current <code>WebSiteRequest</code>
	 *
	 * @return  a <code>String</code> or <code>null</code> for none
	 */
	public String getOnloadScript(WebSiteRequest req) {
		return null;
	}

	/**
	 * Gets all of the pages that are children of this one in the page hierarchy.
	 * Unless overridden, the pages are cached in a <code>WebPage[]</code> for
	 * faster access.  The actual list of pages is obtained from {@link #getChildren(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)}.
	 * <p>
	 * Pages will also not be cached if the configuration property is set to anything
	 * other than <code>"true"</code>
	 *
	 * @return a <code>WebPage[]</code> of all of the lower-level pages
	 *
	 * @see  #getChildren(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 */
	synchronized public WebPage[] getCachedChildren(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		WebPage[] children = this.cachedChildren;
		if(children == null) this.cachedChildren = children = getChildren(req, resp);
		return children;
	}

	/**
	 * Gets the parent of this page or <code>null</code> for none.
	 */
	public abstract WebPage getParent() throws IOException, SQLException;

	/**
	 * Gets the absolute or context-relative URL to direct to.
	 * Redirection happens before specific frameset actions thus allowing one to redirect various frames to different places.
	 *
	 * @return  the context-relative or absolute URL to redirect to or <code>null</code> for
	 *          no redirect.
	 */
	public String getRedirectURL(WebSiteRequest req) throws IOException, SQLException {
		return null;
	}

	/**
	 * Gets the redirect type, defaults to 302 (temporary).
	 */
	public int getRedirectType() {
		return HttpServletResponse.SC_MOVED_TEMPORARILY;
	}

	/**
	 * A short title is used showing a path to the current location in the site.  The short title
	 * defaults to <code>getTitle</code>.
	 *
	 * @return  the short page title
	 *
	 * @see  #getTitle
	 */
	public String getShortTitle() throws IOException, SQLException{
		return getTitle();
	}

	/**
	 * Gets the title of the web page in order to generate the HTML.  The
	 * title defaults to that of the parent page.
	 *
	 * @return  the page title
	 */
	public String getTitle() throws IOException, SQLException {
		return getParent().getTitle();
	}

	/**
	 * Gets parameters that are added to the query string of URLs generated for this page.
	 *
	 * @return  The parameters, may be {@code null} or empty when none.
	 *
	 * @see  EmptyURIParameters#getInstance()
	 */
	public URIParameters getURLParams(WebSiteRequest req) throws IOException, SQLException {
		return null;
	}

	/**
	 * @see  #getWebPage(javax.servlet.ServletContext, java.lang.Class, com.aoindustries.website.framework.WebSiteRequest)
	 */
	public WebPage getWebPage(Class<? extends WebPage> clazz, WebSiteRequest req) throws IOException {
		return getWebPage(getServletContext(), clazz, req);
	}

	/**
	 * Placeholder value used when constructor not found since concurrent maps do not allow null keys.
	 */
	private static final Constructor<? extends WebPage> NO_SUCH_CONSTRUCTOR;
	static {
		try {
			NO_SUCH_CONSTRUCTOR = WebPage.class.getConstructor();
		} catch(NoSuchMethodException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static final ConcurrentMap<Class<? extends WebPage>,Constructor<? extends WebPage>> requestConstructors = new ConcurrentHashMap<>();
	@SuppressWarnings("unchecked")
	private static <W extends WebPage> Constructor<W> getRequestConstructor(Class<W> clazz) {
		Constructor<?> con = (Constructor)requestConstructors.get(clazz);
		if(con == null) {
			try {
				con = clazz.getConstructor(WebSiteRequest.class);
			} catch(NoSuchMethodException e) {
				con = NO_SUCH_CONSTRUCTOR;
			}
			requestConstructors.put(clazz, (Constructor)con);
		}
		return (con == NO_SUCH_CONSTRUCTOR) ? null : (Constructor)con;
	}

	private static final ConcurrentMap<Class<? extends WebPage>,Constructor<? extends WebPage>> paramsConstructors = new ConcurrentHashMap<>();
	@SuppressWarnings("unchecked")
	private static <W extends WebPage> Constructor<W> getParamsConstructor(Class<W> clazz) {
		Constructor<?> con = (Constructor)paramsConstructors.get(clazz);
		if(con == null) {
			try {
				con = clazz.getConstructor(URIParameters.class);
			} catch(NoSuchMethodException e) {
				con = NO_SUCH_CONSTRUCTOR;
			}
			paramsConstructors.put(clazz, (Constructor)con);
		}
		return (con == NO_SUCH_CONSTRUCTOR) ? null : (Constructor)con;
	}

	private static final ConcurrentMap<Class<? extends WebPage>,Constructor<? extends WebPage>> defaultConstructors = new ConcurrentHashMap<>();
	@SuppressWarnings("unchecked")
	private static <W extends WebPage> Constructor<W> getDefaultConstructor(Class<W> clazz) {
		Constructor<?> con = (Constructor)defaultConstructors.get(clazz);
		if(con == null) {
			try {
				con = clazz.getConstructor();
			} catch(NoSuchMethodException e) {
				con = NO_SUCH_CONSTRUCTOR;
			}
			defaultConstructors.put(clazz, (Constructor)con);
		}
		return (con == NO_SUCH_CONSTRUCTOR) ? null : (Constructor)con;
	}

	/**
	 * Gets an instance of <code>WebPage</code> given the <code>Class</code>.
	 * Instances returned should never have the <code>init</code> method
	 * called and should allocate a minimal set of resources.
	 * <p>
	 * Unless caching is disabled, the generated pages are stored in a
	 * cache and resolved using the pages <code>isHandler</code> method.
	 * </p>
	 * <p>
	 * When creating a new instance of {@link WebPage}, searches for constructors in the following order:
	 * </p>
	 * <ol>
	 * <li>Single argument {@link WebSiteRequest}, given the request.</li>
	 * <li>Single argument {@link URIParameters}, given a {@link ServletRequestParameters} wrapper around request.</li>
	 * <li>No-args constructor.</li>
	 * </ol>
	 *
	 * @param  context  the context the servlet will be run in
	 * @param  clazz  the <code>Class</code> to get an instance of
	 * @param  req  the request details are used to select the right instance
	 *
	 * @return  a <code>WebPage</code> object of the given class that matches the request settings
	 *
	 * @exception  IllegalArgumentException if unable to create the instance
	 *
	 * @see  #isHandler(WebSiteRequest)
	 */
	public static WebPage getWebPage(ServletContext context, Class<? extends WebPage> clazz, WebSiteRequest req) throws IOException {
		if(context == null) throw new IllegalArgumentException("context is null");
		synchronized(webPageCache) {
			// First look for a match in the cache
			List<WebPage> cached = webPageCache.get(clazz);
			if(cached != null) {
				for(WebPage page : cached) {
					assert page.getClass() == clazz;
					if(page.isHandler(req)) return page;
				}
			}

			// Make a new instance and store in cache
			WebPage page;
			Constructor<? extends WebPage> con = getRequestConstructor(clazz);
			if(con != null) {
				try {
					page = con.newInstance(req);
				} catch (Error | RuntimeException | ReflectiveOperationException e) {
					throw new IOException("Unable to getWebPage: " + clazz.getName() + ", req=" + req, e);
				}
			} else {
				con = getParamsConstructor(clazz);
				if(con != null) {
					URIParameters params = new ServletRequestParameters(req);
					try {
						page = con.newInstance(params);
					} catch (Error | RuntimeException | ReflectiveOperationException e) {
						throw new IOException("Unable to getWebPage: " + clazz.getName() + ", params=" + params, e);
					}
				} else {
					con = getDefaultConstructor(clazz);
					if(con != null) {
						try {
							page = con.newInstance();
						} catch (Error | RuntimeException | ReflectiveOperationException e) {
							throw new IOException("Unable to getWebPage: " + clazz.getName(), e);
						}
					} else {
						throw new IOException("No constructor found for getWebPage: " + clazz.getName());
					}
				}
			}
			page.setServletContext(context);
			if(cached == null) webPageCache.put(clazz, cached = new ArrayList<>());
			cached.add(page);
			return page;
		}
	}

	/**
	 * @param  params  The parameters used to select the right instance.
	 *
	 * @see  #getWebPage(javax.servlet.ServletContext, java.lang.Class, com.aoindustries.net.URIParameters)
	 */
	public WebPage getWebPage(Class<? extends WebPage> clazz, URIParameters params) throws IOException {
		return getWebPage(getServletContext(), clazz, params);
	}

	/**
	 * Gets a web page given no parameters.
	 *
	 * @see  #getWebPage(java.lang.Class, com.aoindustries.net.URIParameters)
	 */
	public WebPage getWebPage(Class<? extends WebPage> clazz) throws IOException {
		return getWebPage(clazz, (URIParameters)null);
	}

	/**
	 * Gets an instance of <code>WebPage</code> given the <code>Class</code>.
	 * Instances returned should never have the <code>init</code> method
	 * called and should allocate a minimal set of resources.
	 * <p>
	 * Unless caching is disabled, the generated pages are stored in a
	 * cache and resolved using the pages <code>isHander</code> method.
	 * </p>
	 * <ol>
	 * <li>Single argument {@link URIParameters}, given the parameters.</li>
	 * <li>No-args constructor.</li>
	 * </ol>
	 *
	 * @param  context  the context the servlet will be run in
	 * @param  params  The parameters used to select the right instance.
	 *
	 * @return  a <code>WebPage</code> object of the given class that matches the request settings
	 *
	 * @exception  IllegalArgumentException if unable to create the instance
	 *
	 * @see  #isHandler(com.aoindustries.net.URIParameters)
	 */
	// TODO: Deprecate for lambda version
	public static WebPage getWebPage(ServletContext context, Class<? extends WebPage> clazz, URIParameters params) throws IOException {
		if(params == null) params = EmptyURIParameters.getInstance();
		synchronized(webPageCache) {
			// First look for a match in the cache
			List<WebPage> cached = webPageCache.get(clazz);
			if(cached != null) {
				for(WebPage page : cached) {
					assert page.getClass() == clazz;
					if(page.isHandler(params)) return page;
				}
			}

			// Make a new instance and store in cache
			WebPage page;
			Constructor<? extends WebPage> con = getParamsConstructor(clazz);
			if(con != null) {
				try {
					page = con.newInstance(params);
				} catch (Error | RuntimeException | ReflectiveOperationException e) {
					throw new IOException("Unable to getWebPage: " + clazz.getName() + ", params=" + params, e);
				}
			} else {
				con = getDefaultConstructor(clazz);
				if(con != null) {
					try {
						page = con.newInstance();
					} catch (Error | RuntimeException | ReflectiveOperationException e) {
						throw new IOException("Unable to getWebPage: " + clazz.getName(), e);
					}
				} else {
					throw new IOException("No constructor found for getWebPage: " + clazz.getName());
				}
			}
			page.setServletContext(context);
			if(cached == null) webPageCache.put(clazz, cached = new ArrayList<>());
			cached.add(page);
			return page;
		}
	}

	/**
	 * Gets an instance of <code>WebPage</code> given the <code>Class</code>.
	 * Instances returned should never have the <code>init</code> method
	 * called and should allocate a minimal set of resources.
	 * <p>
	 * Unless caching is disabled, the generated pages are stored in a
	 * cache and resolved using the pages <code>isHander</code> method.
	 * </p>
	 * <ol>
	 * <li>Single argument {@link URIParameters}, given empty parameters.</li>
	 * <li>No-args constructor.</li>
	 * </ol>
	 *
	 * @param  context  the context the servlet will be run in
	 *
	 * @return  a <code>WebPage</code> object of the given class that matches the request settings
	 *
	 * @exception  IllegalArgumentException if unable to create the instance
	 *
	 * @see  #getWebPage(javax.servlet.ServletContext, java.lang.Class, com.aoindustries.net.URIParameters)
	 */
	// TODO: Deprecate for lambda version
	public static WebPage getWebPage(ServletContext context, Class<? extends WebPage> clazz) throws IOException {
		return getWebPage(context, clazz, (URIParameters)null);
	}

	/**
	 * Dynamically loads new classes based on the source .class file's modified time.
	 */
	public static Class<? extends WebPage> loadClass(String className) throws ClassNotFoundException {
		return Class.forName(className).asSubclass(WebPage.class);
	}

	/**
	 * Gets the current layout for this page.
	 * When req is null, should return {@link SearchLayout} or equivalent.
	 * <p>
	 * This default implementation returns {@link SearchLayout#getInstance()} for
	 * a search request (req is null), or inherits the layout of the
	 * {@linkplain #getParent() parent}.
	 * </p>
	 *
	 * @param  req  the {@link WebSiteRequest} for this request, or {@code null} when searching
	 *
	 * @return  the <code>WebPageLayout</code>
	 */
	// TODO: Review uses, should be much fewer now (only from this class?)
	public WebPageLayout getWebPageLayout(WebSiteRequest req) throws IOException, SQLException {
		// Search index building
		if(req == null) return SearchLayout.getInstance();
		return getParent().getWebPageLayout(req);
	}

	/**
	 * Gets all of the children pages of this one in the page hierarchy.  Defaults to none.
	 * The results of this call are never cached.  For efficiency, please call
	 * <code>getCachedWebPages</code>.  Subclasses will override and disable the caching
	 * provided by <code>getCachedWebPages</code> when appropriate.
	 *
	 * @return a <code>WebPage[]</code> of all of the lower-level pages
	 *
	 * @see  #getCachedChildren(com.aoindustries.website.framework.WebSiteRequest, javax.servlet.http.HttpServletResponse)
	 * @see  #emptyWebPageArray
	 */
	// TODO: Allow ServletException here, too.  More generally, allow ServletException instead of SQLException
	@SuppressWarnings("ReturnOfCollectionOrArrayField") // Empty array is unmodifiable
	protected WebPage[] getChildren(WebSiteRequest req, HttpServletResponse resp) throws IOException, SQLException {
		return emptyWebPageArray;
	}

	/**
	 * Determines if this page is the instance that should handle a particular request.
	 * <p>
	 * By default calls {@link #isHandler(com.aoindustries.net.URIParameters)}, wrapping request in
	 * {@link ServletRequestParameters}.  When no request, uses {@link EmptyURIParameters}.
	 *
	 * @see  #getWebPage(javax.servlet.ServletContext, java.lang.Class, com.aoindustries.website.framework.WebSiteRequest)
	 */
	public boolean isHandler(WebSiteRequest req) {
		return isHandler((req == null) ? EmptyURIParameters.getInstance() : new ServletRequestParameters(req));
	}

	/**
	 * Determines if this page is the instance that represents a certain set of parameters.
	 * <p>
	 * By default returns <code>true</code>, meaning it is a handler for any parameters
	 * for this <code>Class</code>.
	 * </p>
	 *
	 * @see  #getWebPage(javax.servlet.ServletContext, java.lang.Class, com.aoindustries.net.URIParameters)
	 */
	public boolean isHandler(URIParameters params) {
		return true;
	}

	/**
	 * Gets the <code>WebSiteRequest</code> that handles authentication and other details
	 * of this site.
	 */
	abstract protected WebSiteRequest getWebSiteRequest(HttpServletRequest req) throws ServletException, IOException, SQLException;

	/**
	 * Searches this WebPage and all of its subordinate pages, returning the matches
	 * in a <code>ArrayList</code> with five elements per match.
	 * <ol>
	 *   <li>A <code>String</code> for the absolute URL (including settings)</li>
	 *   <li>The probability as a <code>Float</code></li>
	 *   <li>The title of the page</li>
	 *   <li>The description of the page</li>
	 *   <li>The author of the page</li>
	 * </ol>
	 * Defaults to <code>standardSearch</code>
	 *
	 * @param  words     all of the words that must match
	 * @param  req       the <code>WebSiteRequest</code> containing the users preferences
	 * @param  results   the <code>ArrayList</code> that contains the results
	 * @param  buffer    the <code>SearchOutputStream</code> to use for internal processing
	 *
	 * @see  #standardSearch
	 */
	public void search(
		String[] words,
		WebSiteRequest req,
		HttpServletResponse response,
		List<SearchResult> results,
		CharArrayWriter buffer,
		Set<WebPage> finishedPages
	) throws ServletException, IOException, SQLException {
		standardSearch(words, req, response, results, buffer, finishedPages);
	}

	/**
	 * The standard implementation of the search functionality.
	 *
	 * @see  #search
	 */
	final public void standardSearch(
		String[] words,
		WebSiteRequest req,
		HttpServletResponse resp,
		List<SearchResult> results,
		CharArrayWriter buffer,
		Set<WebPage> finishedPages
	) throws ServletException, IOException, SQLException {
		if(finishedPages.add(this)) {
			String title = null;
			String description = null;
			String author = null;
			String authorHref = null;

			// The counted matches will go here
			int totalMatches = 0;
			int size;

			// Search the byte data only if not able to index
			long mySearchLastModified = getSearchLastModified();
			if (mySearchLastModified == -1) {
				title = getTitle();
				description = getDescription();
				author = getAuthor();
				authorHref = getAuthorHref(req, resp);
				String keywords = getKeywords();

				// Get the HTML content
				buffer.reset();
				// TODO: EncodingContext based on page settings, or XML always for search?
				Html html = new Html(buffer);
				// Isolate page-scope registry
				Registry oldPageRegistry = RegistryEE.Page.get(req);
				try {
					// TODO: Set serialization based on page settings, or XML always for search?
					// TODO: Set doctype based on page settings?
					RegistryEE.Page.set(req, new Registry());
					doGet(null, null, html);
				} finally {
					RegistryEE.Page.set(req, oldPageRegistry);
				}
				String content = buffer.toString();
				size = buffer.size();

				int len = words.length;
				for (int c = 0; c < len; c++) {
					String word = words[c];
					int wordMatch =
						// Add the keywords with weight 10
						(keywords == null ? 0 : (Strings.countOccurrences(keywords, word) * 10))

						// Add the description with weight 5
						+ (description == null ? 0 : (Strings.countOccurrences(description, word) * 5))

						// Add the title with weight 5
						+ (title == null ? 0 : (Strings.countOccurrences(title, word) * 5))

						// Add the content with weight 1
						+ Strings.countOccurrences(content, word)

						// Add the author with weight 1
						+ (author == null ? 0 : Strings.countOccurrences(author, word));

					if (wordMatch == 0) {
						totalMatches = 0;
						break;
					}
					totalMatches += wordMatch;
				}

				if (totalMatches > 0) {
					size +=
						(keywords == null ? 0 : keywords.length())
						+ (description == null ? 0 : description.length())
						+ (title == null ? 0 : title.length())
						+ (author == null ? 0 : author.length());
				}
			} else {
				// Rebuild the search index if no longer valid
				if (mySearchLastModified != this.searchLastModified) {
					// Only synchronize for index rebuild
					synchronized (this) {
						if (mySearchLastModified != this.searchLastModified) {
							title = getTitle();
							description = getDescription();
							author = getAuthor();
							authorHref = getAuthorHref(req, resp);
							String keywords = getKeywords();

							// Get the HTML content
							buffer.reset();
							// TODO: EncodingContext based on page settings, or XML always for search?
							Html html = new Html(buffer);
							// Isolate page-scope registry
							Registry oldPageRegistry = RegistryEE.Page.get(req);
							try {
								RegistryEE.Page.set(req, new Registry());
								// TODO: Set serialization based on page settings, or XML always for search?
								// TODO: Set doctype based on page settings?
								doGet(null, null, html);
							} finally {
								RegistryEE.Page.set(req, oldPageRegistry);
							}
							String content = buffer.toString();

							// Remove all the indexed words
							searchWords.clear();
							searchCounts.clear();

							// Add the keywords with weight 10
							if(keywords != null) addSearchWords(keywords, 10);

							// Add the description with weight 5
							if(description != null) addSearchWords(description, 5);

							// Add the title with weight 5
							if(title != null) addSearchWords(title, 5);

							// Add the content with weight 1
							addSearchWords(content, 1);

							// Add the author with weight 1
							if(author != null) addSearchWords(author, 1);

							searchByteCount =
								content.length()
								+ (keywords == null ? 0 : keywords.length())
								+ (description == null ? 0 : description.length())
								+ (title == null ? 0 : title.length())
								+ (author == null ? 0 : author.length());
							//searchWords.trimToSize();
							//searchCounts.trimToSize();
							this.searchLastModified = mySearchLastModified;
						}
					}
				}

				// Count the words from the index
				int searchWordsSize = searchWords.size();

				int len = words.length;
				for (int c = 0; c < len; c++) {
					String word = words[c];

					// Count through each word
					int wordMatch = 0;
					for (int d = 0; d < searchWordsSize; d++) {
						String searchWord = searchWords.get(d);
						int count = Strings.countOccurrences(searchWord, word);
						if (count > 0) wordMatch += count * searchCounts.get(d)[0];
					}

					if (wordMatch == 0) {
						totalMatches = 0;
						break;
					}
					totalMatches += wordMatch;
				}

				// Use the cached size
				size = searchByteCount;
			}

			if (totalMatches > 0) {
				float probability=
					totalMatches
					/ (
						size <= 0
						? 1.0f :
						((float)Math.log(size))
					)
				;
				results.add(
					new SearchResult(
						req.getURL(this),
						probability,
						title == null ? getTitle() : title,
						description == null ? getDescription() : description,
						author == null ? getAuthor() : author,
						authorHref == null ? getAuthorHref(req, resp) : authorHref
					)
				);
			}

			// Search recursively
			WebPage[] children = getCachedChildren(req, resp);
			int len = children.length;
			for (int c = 0; c < len; c++) {
				children[c].search(words, req, resp, results, buffer, finishedPages);
			}
		}
	}

	/**
	 * Determine if the nav image for this page should remain visible, even when
	 * its children are displayed.  The default is <code>false</code>.
	 */
	public boolean includeNavImageAsParent() {
		return false;
	}

	/**
	 * Determines whether or not to display the page in the left navigation.
	 */
	public boolean useNavImage() throws IOException, SQLException {
		return true;
	}

	/**
	 * Determines if this page will be displayed in the standard site map.
	 */
	public boolean useSiteMap() {
		return true;
	}

	/**
	 * Determines if this page will be displayed in the location bar.
	 */
	public boolean showInLocationPath(WebSiteRequest req) {
		return true;
	}

	private ServletContext context;

	@Override
	public ServletContext getServletContext() {
		if(context!=null) return context;
		ServletContext sc=super.getServletContext();
		if(sc==null) throw new NullPointerException("ServletContext is null");
		return sc;
	}

	final void setServletContext(ServletContext context) {
		this.context = context;
	}

	/**
	 * Gets the copyright information for this page.  Defaults to the copyright of the parent page.
	 * May not contain HTML.
	 *
	 * // TODO: Use dcterms:
	 *          http://stackoverflow.com/questions/6665312/is-the-copyright-meta-tag-valid-in-html5
	 *          https://wiki.whatwg.org/wiki/MetaExtensions
	 *          http://dublincore.org/documents/dcmi-terms/
	 */
	public String getCopyright(WebSiteRequest req, HttpServletResponse resp, WebPage requestPage) throws IOException, SQLException {
		return getParent().getCopyright(req, resp, requestPage);
	}

	/**
	 * Gets the context-relative path for the URL
	 */
	public String getURLPath() throws IOException, SQLException {
		return '/'+generateURLPath(this);
	}

	/**
	 * Generates a URL path for this or another page, please call getURLPath() instead.
	 * The default behavior is to ask the parent to generate the URL.  Therefore the
	 * top-level <code>WebPage</code> of a site must implement this method.
	 */
	public String generateURLPath(WebPage page) throws IOException, SQLException {
		return getParent().generateURLPath(page);
	}

	/**
	 * Gets the URL pattern for this page as used in <code>web.xml</code>.
	 */
	public String getURLPattern() throws IOException, SQLException {
		return getURLPath();
	}
}
