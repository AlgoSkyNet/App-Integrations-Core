/**
 * Copyright 2016-2017 Symphony Integrations - Symphony LLC
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

package org.symphonyoss.integration.provisioning.service;

import static org.symphonyoss.integration.provisioning.properties.ApplicationProperties.FAIL_GET_USER_BY_USERNAME;
import static org.symphonyoss.integration.provisioning.properties.ApplicationProperties.FAIL_SAVE_APP;
import static org.symphonyoss.integration.provisioning.properties.ApplicationProperties.FAIL_UPDATE_APP_SETTINGS;
import static org.symphonyoss.integration.provisioning.properties.AuthenticationProperties.DEFAULT_USER_ID;
import static org.symphonyoss.integration.provisioning.properties.IntegrationProvisioningProperties.FAIL_POD_API_SOLUTION;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.symphonyoss.integration.authentication.AuthenticationProxy;
import org.symphonyoss.integration.entity.model.User;
import org.symphonyoss.integration.exception.RemoteApiException;
import org.symphonyoss.integration.logging.LogMessageSource;
import org.symphonyoss.integration.model.config.IntegrationSettings;
import org.symphonyoss.integration.model.yaml.Application;
import org.symphonyoss.integration.model.yaml.IntegrationProperties;
import org.symphonyoss.integration.pod.api.client.AppEntitlementApiClient;
import org.symphonyoss.integration.pod.api.client.PodHttpApiClient;
import org.symphonyoss.integration.pod.api.model.AppEntitlement;
import org.symphonyoss.integration.provisioning.client.AppRepositoryClient;
import org.symphonyoss.integration.provisioning.client.model.AppStoreBuilder;
import org.symphonyoss.integration.provisioning.client.model.AppStoreWrapper;
import org.symphonyoss.integration.provisioning.exception.AppRepositoryClientException;
import org.symphonyoss.integration.provisioning.exception.ApplicationProvisioningException;
import org.symphonyoss.integration.provisioning.exception.UserSearchException;

import java.net.MalformedURLException;
import java.util.Map;

import javax.annotation.PostConstruct;

/**
 * Service class to setup applications on Symphony store.
 *
 * Created by rsanchez on 18/10/16.
 */
@Service
public class ApplicationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationService.class);

  private static final String APP_ID = "id";

  @Autowired
  private UserService userService;

  @Autowired
  private AppRepositoryClient client;

  @Autowired
  private AuthenticationProxy authenticationProxy;

  @Autowired
  private PodHttpApiClient podApiClient;

  @Autowired
  private IntegrationProperties properties;

  @Autowired
  private LogMessageSource logMessage;
  
  private AppEntitlementApiClient appEntitlementApi;

  @PostConstruct
  public void init() {
    this.appEntitlementApi = new AppEntitlementApiClient(podApiClient, logMessage);
  }

  /**
   * Creates or updates an application on Symphony store.
   * @param settings Integration settings associated with the application.
   * @param application Application object.
   */
  public void setupApplication(IntegrationSettings settings, Application application) {
    String appType = application.getComponent();

    LOGGER.info("Provisioning Symphony store data for application: {}", appType);

    if (settings.getOwner() == null) {
      User user = userService.getUser(appType);

      if (user == null) {
        String message = logMessage.getMessage(FAIL_GET_USER_BY_USERNAME, appType);
        String solution = logMessage.getMessage(FAIL_POD_API_SOLUTION);
        throw new UserSearchException(message, solution);
      }

      settings.setOwner(user.getId());
    }

    try {
      String domain = properties.getIntegrationBridge().getDomain();
      String botUserId = settings.getOwner().toString();

      AppStoreWrapper wrapper =
          AppStoreBuilder.build(application, domain, settings.getConfigurationId(), botUserId);

      Map<String, String> app = client.getAppByAppGroupId(appType, DEFAULT_USER_ID);
      if (app != null) {
        client.updateApp(wrapper, DEFAULT_USER_ID, app.get(APP_ID));
      } else {
        client.createNewApp(wrapper, DEFAULT_USER_ID);
        updateAppSettings(application);
      }
    } catch (AppRepositoryClientException | MalformedURLException e) {
      String message = logMessage.getMessage(FAIL_SAVE_APP, application.getId(), StringUtils.EMPTY);
      String solution = logMessage.getMessage(FAIL_POD_API_SOLUTION);
      throw new ApplicationProvisioningException(message, e, solution);
    }
  }

  /**
   * Update application settings on Symphony store.
   * @param application Application object
   * @return true if the application was updated or false otherwise.
   */
  public boolean updateAppSettings(Application application) {
    String appType = application.getComponent();
    LOGGER.info("Updating application settings: {}", appType);

    String sessionToken = authenticationProxy.getSessionToken(DEFAULT_USER_ID);

    try {
      Map<String, String> app = client.getAppByAppGroupId(appType, DEFAULT_USER_ID);

      if (app != null) {
        AppEntitlement appEntitlement = new AppEntitlement();
        appEntitlement.setAppId(appType);
        appEntitlement.setAppName(application.getName());
        appEntitlement.setEnable(application.isEnabled());
        appEntitlement.setListed(application.isVisible());
        appEntitlement.setInstall(Boolean.FALSE);

        appEntitlementApi.updateAppEntitlement(sessionToken, appEntitlement);

        return true;
      } else {
        return false;
      }
    } catch (AppRepositoryClientException | RemoteApiException e) {
      String message = logMessage.getMessage(FAIL_UPDATE_APP_SETTINGS, appType);
      String solution = logMessage.getMessage(FAIL_POD_API_SOLUTION);
      throw new ApplicationProvisioningException(message, e, solution);
    }
  }
}
