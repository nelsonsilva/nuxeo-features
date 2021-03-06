/*
 * (C) Copyright 2010 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thierry Delprat
 *     Julien Carsique
 *
 * $Id$
 */

package org.nuxeo.ecm.admin.setup;

import static org.nuxeo.launcher.config.ConfigurationGenerator.PARAM_TEMPLATE_DBNAME;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;

import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.component.ValueHolder;
import javax.faces.context.FacesContext;
import javax.faces.event.AbortProcessingException;
import javax.faces.event.ActionEvent;
import javax.faces.validator.ValidatorException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Factory;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.faces.FacesMessages;
import org.jboss.seam.international.StatusMessage;
import org.nuxeo.ecm.platform.ui.web.util.ComponentUtils;
import org.nuxeo.launcher.commons.DatabaseDriverException;
import org.nuxeo.launcher.config.ConfigurationException;
import org.nuxeo.launcher.config.ConfigurationGenerator;

/**
 * Serves UI for the setup screen, handling properties that can be saved on the
 * bin/nuxeo.conf file on server.
 * <p>
 * Manages some important parameters to perform validation on them, and accepts
 * custom parameters that would be present in the server nuxeo.conf file, and
 * moves to advanced mode any property that would not be in that list.
 *
 * @since 5.5
 */
@Scope(ScopeType.SESSION)
@Name("setupWizardAction")
public class SetupWizardActionBean implements Serializable {

    private static final long serialVersionUID = 1L;

    protected static final Log log = LogFactory.getLog(SetupWizardActionBean.class);

    /**
     * The list of important parameters that need to be presented first to the
     * user
     */
    private static final String[] managedKeyParameters = {
            "nuxeo.bind.address", "nuxeo.url", "nuxeo.data.dir",
            "nuxeo.log.dir", "org.nuxeo.ecm.product.name",
            "org.nuxeo.ecm.product.version", "nuxeo.conf",
            PARAM_TEMPLATE_DBNAME, "nuxeo.db.name", "nuxeo.db.user",
            "nuxeo.db.password", "nuxeo.db.host", "nuxeo.db.port",
            "nuxeo.db.min-pool-size", "nuxeo.db.min-pool-size",
            "nuxeo.db.max-pool-size", "nuxeo.vcs.min-pool-size",
            "nuxeo.vcs.max-pool-size", "nuxeo.notification.eMailSubjectPrefix",
            "mailservice.user", "mailservice.password", "mail.store.protocol",
            "mail.transport.protocol", "mail.store.host", "mail.store.port",
            "mail.store.user", "mail.store.password", "mail.debug",
            "mail.transport.host", "mail.transport.port",
            "mail.transport.auth", "mail.transport.user",
            "mail.transport.password", "mail.from", "mail.user",
            "mail.transport.usetls", "nuxeo.http.proxy.host",
            "nuxeo.http.proxy.port", "nuxeo.http.proxy.login",
            "nuxeo.http.proxy.password", "org.nuxeo.dev" };

    protected Map<String, String> parameters;

    protected Map<String, String> advancedParameters;

    protected static final String PROXY_NONE = "none";

    protected static final String PROXY_ANONYMOUS = "anonymous";

    protected static final String PROXY_AUTHENTICATED = "authenticated";

    private static final String ERROR_DB_DRIVER = "error.db.driver.notfound";

    private static final String ERROR_DB_CONNECTION = "error.db.connection";

    private static final String ERROR_DB_FS = "error.db.fs";

    protected String proxyType = PROXY_NONE;

    protected boolean needsRestart = false;

    @In(create = true)
    private transient ConfigurationGenerator setupConfigGenerator;

    protected Properties userConfig;

    @In(create = true, required = false)
    protected FacesMessages facesMessages;

    @In(create = true)
    protected Map<String, String> messages;

    @Factory(value = "setupRequiresRestart", scope = ScopeType.EVENT)
    public boolean isNeedsRestart() {
        return needsRestart;
    }

    public void setNeedsRestart(boolean needsRestart) {
        this.needsRestart = needsRestart;
    }

    @Factory(value = "setupConfigGenerator", scope = ScopeType.PAGE)
    public ConfigurationGenerator getConfigurationGenerator() {
        if (setupConfigGenerator == null) {
            setupConfigGenerator = new ConfigurationGenerator();
            if (setupConfigGenerator.init()) {
                setParameters();
            }
        }
        return setupConfigGenerator;
    }

    @Factory(value = "setupConfigurable", scope = ScopeType.APPLICATION)
    public boolean isConfigurable() {
        return setupConfigGenerator.isConfigurable();
    }

    @Factory(value = "advancedParams", scope = ScopeType.EVENT)
    public Map<String, String> getAdvancedParameters() {
        return advancedParameters;
    }

    @Factory(value = "setupParams", scope = ScopeType.EVENT)
    public Map<String, String> getParameters() {
        return parameters;
    }

    /**
     * Fill {@link #parameters} and {@link #advancedParameters} with properties
     * from #{@link ConfigurationGenerator#getUserConfig()}
     *
     * @since 5.6
     */
    protected void setParameters() {
        userConfig = setupConfigGenerator.getUserConfig();
        parameters = new HashMap<String, String>();
        advancedParameters = new TreeMap<String, String>();
        // will remove managed parameters later in setParameter()
        for (String key : userConfig.stringPropertyNames()) {
            if (System.getProperty(key) == null
                    || key.matches("^(nuxeo|org\\.nuxeo|catalina|derby|h2|java\\.home|"
                            + "java\\.io\\.tmpdir|tomcat|sun\\.rmi\\.dgc).*")) {
                advancedParameters.put(key, userConfig.getProperty(key).trim());
            }
        }
        for (String keyParam : managedKeyParameters) {
            String parameter = userConfig.getProperty(keyParam);
            setParameter(keyParam, parameter);
        }

        proxyType = PROXY_NONE;
        if (parameters.get("nuxeo.http.proxy.host") != null) {
            proxyType = PROXY_ANONYMOUS;
            if (parameters.get("nuxeo.http.proxy.login") != null) {
                proxyType = PROXY_AUTHENTICATED;
            }
        }
    }

    /**
     * Adds parameter value to the
     *
     * @param key parameter key such as used in templates and nuxeo.conf
     */
    private void setParameter(String key, String value) {
        if (value != null) {
            parameters.put(key, value.trim());
            advancedParameters.remove(key);
        }
    }

    public void save() {
        saveParameters();
        setNeedsRestart(true);
        resetParameters();
        facesMessages.add(StatusMessage.Severity.INFO,
                messages.get("label.parameters.saved"));
    }

    @SuppressWarnings("unchecked")
    protected void saveParameters() {
        // Fix types (replace Long and Boolean with String)
        for (Iterator<?> entries = parameters.entrySet().iterator(); entries.hasNext();) {
            @SuppressWarnings("rawtypes")
            Entry entry = (Entry<String, ?>) entries.next();
            if (entry.getValue() instanceof Long) {
                entry.setValue(((Long) entry.getValue()).toString());
            }
            if (entry.getValue() instanceof Boolean) {
                entry.setValue(((Boolean) entry.getValue()).toString());
            }
        }

        // manage httpProxy settings (setting null is not accepted)
        if (!PROXY_AUTHENTICATED.equals(proxyType)) {
            parameters.put("nuxeo.http.proxy.login", "");
            parameters.put("nuxeo.http.proxy.password", "");
        }
        if (PROXY_NONE.equals(proxyType)) {
            parameters.put("nuxeo.http.proxy.host", "");
            parameters.put("nuxeo.http.proxy.port", "");
        }

        // Remove empty values for password keys
        for (String pwdKey : new String[] { "nuxeo.db.password",
                "mailservice.password", "mail.transport.password",
                "nuxeo.http.proxy.password" }) {
            if (StringUtils.isEmpty(parameters.get(pwdKey))) {
                parameters.remove(pwdKey);
            }
        }

        Map<String, String> customParameters = new HashMap<String, String>();
        customParameters.putAll(parameters);
        customParameters.putAll(advancedParameters);
        try {
            setupConfigGenerator.saveFilteredConfiguration(customParameters);
        } catch (ConfigurationException e) {
            log.error(e, e);
        }
    }

    public void resetParameters() {
        setupConfigGenerator = null;
        parameters = null;
        advancedParameters = null;
        Contexts.getPageContext().remove("setupConfigGenerator");
    }

    /**
     * @since 5.6
     */
    public void checkDatabaseParameters(FacesContext context,
            UIComponent component, Object value) {
        Map<String, Object> attributes = component.getAttributes();
        String dbNameInputId = (String) attributes.get("dbNameInputId");
        String dbUserInputId = (String) attributes.get("dbUserInputId");
        String dbPwdInputId = (String) attributes.get("dbPwdInputId");
        String dbHostInputId = (String) attributes.get("dbHostInputId");
        String dbPortInputId = (String) attributes.get("dbPortInputId");

        if (dbNameInputId == null || dbUserInputId == null
                || dbPwdInputId == null || dbHostInputId == null
                || dbPortInputId == null) {
            log.error("Cannot validate database parameters: missing inputIds");
            return;
        }

        UIInput dbNameComp = (UIInput) component.findComponent(dbNameInputId);
        UIInput dbUserComp = (UIInput) component.findComponent(dbUserInputId);
        UIInput dbPwdComp = (UIInput) component.findComponent(dbPwdInputId);
        UIInput dbHostComp = (UIInput) component.findComponent(dbHostInputId);
        UIInput dbPortComp = (UIInput) component.findComponent(dbPortInputId);
        if (dbNameComp == null || dbUserComp == null || dbPwdComp == null
                || dbHostComp == null || dbPortComp == null) {
            log.error("Cannot validate inputs: not found");
            return;
        }

        String dbName = (String) dbNameComp.getLocalValue();
        String dbUser = (String) dbUserComp.getLocalValue();
        String dbPwd = (String) dbPwdComp.getLocalValue();
        String dbHost = (String) dbHostComp.getLocalValue();
        Long dbPortLong = (Long) dbPortComp.getLocalValue();
        String dbPort = dbPortLong.toString();

        if (StringUtils.isEmpty(dbPwd)) {
            dbPwd = parameters.get("nuxeo.db.password");
        }

        String errorLabel = null;
        Exception error = null;
        try {
            setupConfigGenerator.checkDatabaseConnection(
                    parameters.get(ConfigurationGenerator.PARAM_TEMPLATE_DBNAME),
                    dbName, dbUser, dbPwd, dbHost, dbPort);
        } catch (FileNotFoundException e) {
            errorLabel = ERROR_DB_FS;
            error = e;
        } catch (IOException e) {
            errorLabel = ERROR_DB_FS;
            error = e;
        } catch (DatabaseDriverException e) {
            errorLabel = ERROR_DB_DRIVER;
            error = e;
        } catch (SQLException e) {
            errorLabel = ERROR_DB_CONNECTION;
            error = e;
        }
        if (error != null) {
            log.error(error, error);
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR, ComponentUtils.translate(
                            context, errorLabel), null);
            throw new ValidatorException(message);
        }

        FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_INFO,
                ComponentUtils.translate(context, "error.db.none"), null);
        message.setSeverity(FacesMessage.SEVERITY_INFO);
        context.addMessage(component.getClientId(context), message);
    }

    public void templateChange(ActionEvent event) {
        String dbTemplate;
        UIComponent select = event.getComponent().getParent();
        if (select instanceof ValueHolder) {
            dbTemplate = (String) ((ValueHolder) select).getValue();
        } else {
            log.error("Bad component returned " + select);
            throw new AbortProcessingException("Bad component returned "
                    + select);
        }
        setupConfigGenerator.changeDBTemplate(dbTemplate);
        setParameters();
        Contexts.getEventContext().remove("setupParams");
        Contexts.getEventContext().remove("advancedParams");
        FacesContext context = FacesContext.getCurrentInstance();
        context.renderResponse();
    }

    public void proxyChange(ActionEvent event) {
        UIComponent select = event.getComponent().getParent();
        if (select instanceof ValueHolder) {
            proxyType = (String) ((ValueHolder) select).getValue();
        } else {
            log.error("Bad component returned " + select);
            throw new AbortProcessingException("Bad component returned "
                    + select);
        }
        Contexts.getEventContext().remove("setupParams");
        FacesContext context = FacesContext.getCurrentInstance();
        context.renderResponse();
    }

    /**
     * Initialized by {@link #getParameters()}
     */
    public String getProxyType() {
        return proxyType;
    }

    public void setProxyType(String proxyType) {
        this.proxyType = proxyType;
    }

}
