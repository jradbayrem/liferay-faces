/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */
package com.liferay.faces.bridge.context.url;

import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.faces.render.ResponseStateManager;
import javax.portlet.BaseURL;
import javax.portlet.PortletMode;
import javax.portlet.PortletModeException;
import javax.portlet.PortletRequest;
import javax.portlet.PortletSecurityException;
import javax.portlet.PortletURL;
import javax.portlet.WindowState;
import javax.portlet.WindowStateException;
import javax.portlet.faces.Bridge;

import com.liferay.faces.bridge.BridgeConstants;
import com.liferay.faces.bridge.config.BridgeConfig;
import com.liferay.faces.bridge.context.BridgeContext;
import com.liferay.faces.bridge.context.ExternalContextImpl;
import com.liferay.faces.bridge.helper.BooleanHelper;
import com.liferay.faces.bridge.helper.PortletModeHelper;
import com.liferay.faces.bridge.helper.WindowStateHelper;
import com.liferay.faces.bridge.logging.Logger;
import com.liferay.faces.bridge.logging.LoggerFactory;


/**
 * This is a utility class used only by {@link ExternalContextImpl} that represents a URL with attributes that the
 * Bridge Spec is concerned with. The getter methods in this class make heavy use of lazy-initialization for performance
 * reasons, because it is unlikely that every method will be called.
 *
 * @author  Neil Griffin
 */
public abstract class BridgeURLBaseImpl implements BridgeURL {

	// Logger
	private static final Logger logger = LoggerFactory.getLogger(BridgeURLBaseImpl.class);

	// Protected Constants
	protected static final String PORTLET_ACTION = "portlet:action";
	protected static final String PORTLET_RENDER = "portlet:render";
	protected static final String PORTLET_RESOURCE = "portlet:resource";
	protected static final String RELATIVE_PATH_PREFIX = "../";

	// Private Data Members
	private String contextPath;
	private String currentFacesViewId;
	private String contextRelativePath;
	private Boolean escaped;
	private Boolean external;
	private Boolean facesViewTarget;
	private Boolean hierarchical;
	private boolean selfReferencing;
	private Map<String, String[]> parameters;
	private Boolean pathRelative;
	private Boolean portletScheme;
	private Bridge.PortletPhase portletPhase;
	private Map<String, String[]> preservedActionParams;
	private boolean secure;
	private URI uri;

	// Protected Data Members
	protected BridgeConfig bridgeConfig;
	protected String url;

	// Protected Data Members
	protected BridgeContext bridgeContext;

	public BridgeURLBaseImpl(String url, String currentFacesViewId, BridgeContext bridgeContext) {
		this.url = url;
		this.bridgeContext = bridgeContext;
		this.contextPath = bridgeContext.getPortletRequest().getContextPath();
		this.bridgeConfig = bridgeContext.getBridgeConfig();
		this.currentFacesViewId = currentFacesViewId;
		this.preservedActionParams = bridgeContext.getPreservedActionParams();
	}

	public String removeParameter(String name) {
		String[] values = getParameterMap().remove(name);
		String value = null;

		if ((values != null) && (values.length > 0)) {
			value = values[0];
		}

		return value;
	}

	@Override
	public String toString() {

		String stringValue = null;

		try {

			// Ask the Portlet Container for a BaseURL that contains the modified parameters.
			BaseURL baseURL = toBaseURL();

			// If the URL string has escaped characters (like %20 for space, etc) then ask the
			// portlet container to create an escaped representation of the URL string.
			if (isEscaped()) {
				StringWriter urlWriter = new StringWriter();

				try {
					baseURL.write(urlWriter, true);
				}
				catch (IOException e) {
					logger.error(e);
					stringValue = baseURL.toString();
				}

				stringValue = urlWriter.toString();
			}

			// Otherwise, ask the portlet container to create a normal (non-escaped) string
			// representation of the URL string.
			else {
				stringValue = baseURL.toString();
			}
		}
		catch (MalformedURLException e) {
			logger.error(e);
		}

		return stringValue;
	}

	/**
	 * Returns a {@link BaseURL} representation of the bridge URL.
	 *
	 * @throws  MalformedURLException
	 */
	protected abstract BaseURL toBaseURL() throws MalformedURLException;

	protected String _toString(boolean modeChanged) {
		return _toString(modeChanged, null);
	}

	protected String _toString(boolean modeChanged, Set<String> excludedParameterNames) {

		StringBuilder buf = new StringBuilder();

		int endPos = url.indexOf(BridgeConstants.CHAR_QUESTION_MARK);

		if (endPos < 0) {
			endPos = url.length();
		}

		if (isPortletScheme()) {
			Bridge.PortletPhase urlPortletPhase = getPortletPhase();

			if (urlPortletPhase == Bridge.PortletPhase.ACTION_PHASE) {
				buf.append(url.substring(PORTLET_ACTION.length(), endPos));
			}
			else if (urlPortletPhase == Bridge.PortletPhase.RENDER_PHASE) {
				buf.append(url.substring(PORTLET_RENDER.length(), endPos));
			}
			else {
				buf.append(url.substring(PORTLET_RESOURCE.length(), endPos));
			}
		}
		else {
			buf.append(url.subSequence(0, endPos));
		}

		boolean firstParam = true;

		buf.append(BridgeConstants.CHAR_QUESTION_MARK);

		Set<String> parameterNames = getParameterNames();

		boolean foundFacesViewIdParam = false;
		boolean foundFacesViewPathParam = false;

		for (String parameterName : parameterNames) {

			boolean addParameter = false;
			String parameterValue = getParameter(parameterName);

			if (Bridge.PORTLET_MODE_PARAMETER.equals(parameterName)) {

				// Only add the "javax.portlet.faces.PortletMode" parameter if it has a valid value.
				addParameter = PortletModeHelper.isValid(parameterValue);
			}
			else if (Bridge.PORTLET_SECURE_PARAMETER.equals(parameterName)) {
				addParameter = BooleanHelper.isBooleanToken(parameterValue);
			}
			else if (Bridge.PORTLET_WINDOWSTATE_PARAMETER.equals(parameterName)) {
				addParameter = WindowStateHelper.isValid(parameterValue);
			}
			else {

				if (!foundFacesViewIdParam) {
					foundFacesViewIdParam = Bridge.FACES_VIEW_ID_PARAMETER.equals(parameterName);
				}

				if (!foundFacesViewPathParam) {
					foundFacesViewPathParam = Bridge.FACES_VIEW_PATH_PARAMETER.equals(parameterName);
				}

				addParameter = true;
			}

			if ((addParameter) &&
					((excludedParameterNames == null) || !excludedParameterNames.contains(parameterName))) {

				if (firstParam) {
					firstParam = false;
				}
				else {
					buf.append(BridgeConstants.CHAR_AMPERSAND);
				}

				buf.append(parameterName);
				buf.append(BridgeConstants.CHAR_EQUALS);
				buf.append(parameterValue);
			}
		}

		// If the "_jsfBridgeViewId" and "_jsfBridgeViewPath" parameters are not present in the URL, then add a
		// parameter that indicates the target Faces viewId.
		if (!foundFacesViewIdParam && !foundFacesViewPathParam && isFacesViewTarget()) {

			if (!isPortletScheme()) {

				// Note that if the "javax.portlet.faces.PortletMode" parameter is specified, then a mode change is
				// being requested and the target Faces viewId parameter must NOT be added.
				if (!modeChanged) {

					if (!firstParam) {
						buf.append(BridgeConstants.CHAR_AMPERSAND);
					}

					buf.append(getViewIdParameterName());

					buf.append(BridgeConstants.CHAR_EQUALS);
					buf.append(getContextRelativePath());
				}
			}
		}

		return buf.toString();
	}

	/**
	 * Determines whether or not the specified files have the same path (prefix) and extension (suffix).
	 *
	 * @param   filePath1  The first file to compare.
	 * @param   filePath2  The second file to compare.
	 *
	 * @return  <code>true</code> if the specified files have the same path (prefix) and extension (suffix), otherwise
	 *          <code>false</code>.
	 */
	protected boolean matchPathAndExtension(String file1, String file2) {

		boolean match = false;

		String path1 = null;
		int lastSlashPos = file1.lastIndexOf(BridgeConstants.CHAR_FORWARD_SLASH);

		if (lastSlashPos > 0) {
			path1 = file1.substring(0, lastSlashPos);
		}

		String path2 = null;
		lastSlashPos = file2.lastIndexOf(BridgeConstants.CHAR_FORWARD_SLASH);

		if (lastSlashPos > 0) {
			path2 = file2.substring(0, lastSlashPos);
		}

		if (((path1 == null) && (path2 == null)) || ((path1 != null) && (path2 != null) && path1.equals(path2))) {

			String ext1 = null;
			int lastDotPos = file1.indexOf(BridgeConstants.CHAR_PERIOD);

			if (lastDotPos > 0) {
				ext1 = file1.substring(lastDotPos);
			}

			String ext2 = null;
			lastDotPos = file2.indexOf(BridgeConstants.CHAR_PERIOD);

			if (lastDotPos > 0) {
				ext2 = file2.substring(lastDotPos);
			}

			if (((ext1 == null) && (ext2 == null)) || ((ext1 != null) && (ext2 != null) && ext1.equals(ext2))) {
				match = true;
			}
		}

		return match;
	}

	public String getContextRelativePath() {

		if (contextRelativePath == null) {

			// If the URL is external, then there is no such thing as a context-relative path in this URL. In this case,
			// return an empty string so that lazy-initialization doesn't take place again.
			if (isExternal()) {
				contextRelativePath = BridgeConstants.EMPTY;
			}

			// Otherwise,
			else {
				String path = getURI().getPath();

				if ((path != null) && (path.length() > 0)) {

					// If the context-path is present, then remove it since we want the return value to be a path that
					// is relative to the context-path.
					int contextPathPos = path.indexOf(contextPath);

					if (contextPathPos >= 0) {
						contextRelativePath = path.substring(contextPathPos + contextPath.length());
					}
					else {
						contextRelativePath = path;
					}
				}
				else {
					contextRelativePath = currentFacesViewId;
				}
			}
		}

		return contextRelativePath;
	}

	public boolean isEscaped() {

		if (escaped == null) {

			escaped = Boolean.FALSE;

			int questionMarkPos = url.indexOf(BridgeConstants.CHAR_QUESTION_MARK);

			if (questionMarkPos > 0) {

				int ampersandPos = url.indexOf(BridgeConstants.CHAR_AMPERSAND, questionMarkPos);

				while (ampersandPos > questionMarkPos) {

					String subURL = url.substring(ampersandPos);

					if (subURL.startsWith("&amp;")) {
						escaped = Boolean.TRUE;
						ampersandPos = url.indexOf(BridgeConstants.CHAR_AMPERSAND, ampersandPos + 1);
					}
					else {
						escaped = Boolean.FALSE;

						break;
					}
				}
			}
		}

		return escaped;
	}

	/**
	 * Determines whether or not the URL is absolute, meaning it contains a scheme component. Note that according to the
	 * class-level documentation of {@link java.net.URI} an absolute URL is non-relative.
	 *
	 * @return  Returns true if the URL is absolute, otherwise returns false.
	 */
	public boolean isAbsolute() {
		return getURI().isAbsolute();
	}

	public boolean isOpaque() {
		return getURI().isOpaque();
	}

	public boolean isPathRelative() {

		if (pathRelative == null) {

			pathRelative = Boolean.FALSE;

			String path = getURI().getPath();

			if ((path != null) && (path.length() > 0) &&
					(!path.startsWith(BridgeConstants.CHAR_FORWARD_SLASH) || path.startsWith(RELATIVE_PATH_PREFIX))) {
				pathRelative = Boolean.TRUE;
			}

		}

		return pathRelative;
	}

	public boolean isPortletScheme() {

		if (portletScheme == null) {
			portletScheme = "portlet".equals(getURI().getScheme());
		}

		return portletScheme;
	}

	public boolean isSecure() {
		return secure;
	}

	/**
	 * Determines whether or not the URL is relative, meaning it does not have a scheme component. Note that according
	 * to the class-level documentation of {@link java.net.URI} a relative URL is non-absolute.
	 *
	 * @return  Returns true if the URL is relative, otherwise returns false.
	 */
	protected boolean isRelative() {
		return !isAbsolute();
	}

	public boolean isSelfReferencing() {
		return selfReferencing;
	}

	public boolean isExternal() {

		if (external == null) {

			external = Boolean.FALSE;

			if (!isPortletScheme()) {

				if (isAbsolute()) {
					external = Boolean.TRUE;
				}
				else {

					if (!url.startsWith(BridgeConstants.CHAR_FORWARD_SLASH) && !url.startsWith(RELATIVE_PATH_PREFIX)) {
						external = Boolean.TRUE;
					}
				}
			}
		}

		return external;
	}

	public boolean isHierarchical() {

		if (hierarchical == null) {

			hierarchical = Boolean.FALSE;

			if ((isAbsolute() && getSchemeSpecificPart().startsWith(BridgeConstants.CHAR_FORWARD_SLASH)) ||
					isRelative()) {
				hierarchical = Boolean.TRUE;
			}
		}

		return hierarchical;
	}

	public String getParameter(String name) {
		String value = null;
		Map<String, String[]> parameterMap = getParameterMap();
		String[] values = parameterMap.get(name);

		if ((values != null) && (values.length > 0)) {
			value = values[0];
		}

		return value;
	}

	public void setParameter(String name, String[] value) {
		getParameterMap().put(name, value);
	}

	public void setParameter(String name, String value) {
		getParameterMap().put(name, new String[] { value });
	}

	public Map<String, String[]> getParameterMap() {

		if (parameters == null) {
			parameters = new HashMap<String, String[]>();

			if (url != null) {
				int pos = url.indexOf("?");

				if (pos > 0) {
					String queryString = url.substring(pos + 1);
					queryString = queryString.replaceAll("&amp;", "&");

					if ((queryString != null) && (queryString.length() > 0)) {

						String[] queryParameters = queryString.split("[&]");

						for (String queryParameter : queryParameters) {
							String[] nameValueArray = queryParameter.split("[=]");

							if (nameValueArray != null) {

								if (nameValueArray.length == 1) {
									String name = nameValueArray[0];
									String value = BridgeConstants.EMPTY;
									parameters.put(name, new String[] { value });
								}
								else if (nameValueArray.length == 2) {
									String name = nameValueArray[0];
									String value = nameValueArray[1];
									parameters.put(name, new String[] { value });
								}
								else {
									logger.error("Invalid name=value pair=[{0}] in URL=[{1}]", nameValueArray, url);
								}
							}
						}
					}
				}
			}
		}

		return parameters;
	}

	public Set<String> getParameterNames() {
		Map<String, String[]> parameterMap = getParameterMap();

		return parameterMap.keySet();
	}

	protected void setPortletModeParameter(String portletMode, PortletURL portletURL) {

		if (portletMode != null) {

			try {
				portletURL.setPortletMode(new PortletMode(portletMode));
			}
			catch (PortletModeException e) {
				logger.error(e);
			}
		}
	}

	public Bridge.PortletPhase getPortletPhase() {

		if (portletPhase == null) {

			if (url != null) {

				if (isPortletScheme()) {

					if (url.startsWith(PORTLET_ACTION)) {
						portletPhase = Bridge.PortletPhase.ACTION_PHASE;
					}
					else if (url.startsWith(PORTLET_RENDER)) {
						portletPhase = Bridge.PortletPhase.RENDER_PHASE;
					}
					else if (url.startsWith(PORTLET_RESOURCE)) {
						portletPhase = Bridge.PortletPhase.RESOURCE_PHASE;
					}
					else {
						portletPhase = Bridge.PortletPhase.RESOURCE_PHASE;
						logger.warn("Invalid keyword after 'portlet:' in URL=[{0}]", url);
					}
				}
			}
			else {
				portletPhase = Bridge.PortletPhase.RESOURCE_PHASE;
				logger.warn("Unable to determine portlet phase in null URL");
			}
		}

		return portletPhase;
	}

	protected void setRenderParameters(BaseURL baseURL) {

		// Get the modified parameter map.
		Map<String, String[]> urlParameterMap = getParameterMap();

		// Copy the public render parameters of the current view to the BaseURL.
		PortletRequest portletRequest = bridgeContext.getPortletRequest();
		Map<String, String[]> publicParameterMap = portletRequest.getPublicParameterMap();

		if (publicParameterMap != null) {
			Set<Entry<String, String[]>> publicParamterMapEntrySet = publicParameterMap.entrySet();

			for (Entry<String, String[]> mapEntry : publicParamterMapEntrySet) {
				String publicParameterName = mapEntry.getKey();

				// Note that preserved action parameters, parameters that already exist in the URL string,
				// and "javax.faces.ViewState" must not be copied.
				if (!ResponseStateManager.VIEW_STATE_PARAM.equals(publicParameterName) &&
						!preservedActionParams.containsKey(publicParameterName) &&
						!urlParameterMap.containsKey(publicParameterName)) {
					baseURL.setParameter(publicParameterName, mapEntry.getValue());
				}
			}
		}

		// Copy the private render parameters of the current view to the BaseURL.
		Map<String, String[]> privateParameterMap = portletRequest.getPrivateParameterMap();

		if (privateParameterMap != null) {
			Set<Entry<String, String[]>> privateParameterMapEntrySet = privateParameterMap.entrySet();

			for (Entry<String, String[]> mapEntry : privateParameterMapEntrySet) {
				String privateParameterName = mapEntry.getKey();

				// Note that preserved action parameters, parameters that already exist in the URL string,
				// and "javax.faces.ViewState" must not be copied.
				if (!ResponseStateManager.VIEW_STATE_PARAM.equals(privateParameterName) &&
						!preservedActionParams.containsKey(privateParameterName) &&
						!urlParameterMap.containsKey(privateParameterName)) {
					baseURL.setParameter(privateParameterName, mapEntry.getValue());
				}
			}
		}
	}

	/**
	 * Returns the scheme-specific part of the URI. For example, the URI "http://www.liferay.com/foo/bar.png" would
	 * return "//www.liferay.com/foo/bar.png".
	 */
	protected String getSchemeSpecificPart() {
		return getURI().getSchemeSpecificPart();
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	protected void setSecureParameter(String secure, BaseURL baseURL) {

		if (secure != null) {

			try {
				baseURL.setSecure(BooleanHelper.toBoolean(secure));
			}
			catch (PortletSecurityException e) {
				logger.error(e);
			}
		}
	}

	public void setSelfReferencing(boolean selfReferencing) {
		this.selfReferencing = selfReferencing;
	}

	public boolean isFacesViewTarget() {

		if (facesViewTarget == null) {

			String potentialFacesViewId = getContextRelativePath();

			if ((currentFacesViewId != null) && (currentFacesViewId.equals(potentialFacesViewId))) {
				facesViewTarget = Boolean.TRUE;
			}
			else {

				// If the context relative view path maps to an actual Faces View due to a serlvet-mapping entry, then
				// return true.
				potentialFacesViewId = bridgeContext.getFacesViewIdFromPath(potentialFacesViewId);

				if (potentialFacesViewId != null) {
					facesViewTarget = Boolean.TRUE;
				}

				// Otherwise,
				else {

					// NOTE: It might be (as in the case of the TCK) that a navigation-rule has fired, and the developer
					// specified something like <to-view-id>/somepath/foo.jsp</to-view-id> instead of using the
					// appropriate extension mapped suffix like <to-view-id>/somepath/foo.jsf</to-view-id>.
					potentialFacesViewId = getContextRelativePath();

					if ((currentFacesViewId != null) &&
							(matchPathAndExtension(currentFacesViewId, potentialFacesViewId))) {
						logger.debug(
							"Regarding path=[{0}] as a Faces view since it has the same path and extension as the current viewId=[{1}]",
							potentialFacesViewId, currentFacesViewId);
						facesViewTarget = Boolean.TRUE;
					}
					else {
						facesViewTarget = Boolean.FALSE;
					}
				}
			}

		}

		return facesViewTarget;
	}

	protected URI getURI() {

		if (uri == null) {

			try {
				uri = new URI(url);
			}
			catch (URISyntaxException e1) {
				logger.error(e1.getMessage());

				try {
					uri = new URI(BridgeConstants.EMPTY);
				}
				catch (URISyntaxException e2) {
					// ignore -- will never happen
				}
			}
		}

		return uri;
	}

	protected String getViewIdParameterName() {

		if (isPortletScheme() && (getPortletPhase() == Bridge.PortletPhase.RESOURCE_PHASE)) {
			return bridgeConfig.getViewIdResourceParameterName();
		}
		else {
			return bridgeConfig.getViewIdRenderParameterName();
		}
	}

	protected void setWindowStateParameter(String windowState, PortletURL portletURL) {

		if (windowState != null) {

			try {
				portletURL.setWindowState(new WindowState(windowState));
			}
			catch (WindowStateException e) {
				logger.error(e);
			}
		}

	}

}
