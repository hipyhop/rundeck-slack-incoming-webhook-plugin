/*
 * Copyright 2014 Andrew Karpow
 * based on Slack Plugin from Hayden Bakkum
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.bitplaces.rundeck.plugins.slack;

import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;
import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.core.Configurable;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

/**
 * Sends Rundeck job notification messages to a Slack channel.
 *
 * @author Hayden Bakkum
 */
@Plugin(service= "Notification", name="SlackNotification")
@PluginDescription(title="Slack Incoming WebHook", description="Sends Rundeck Notifications to Slack")
public class SlackNotificationPlugin implements NotificationPlugin {

    private static final String SLACK_MESSAGE_COLOR_GREEN = "good";
    private static final String SLACK_MESSAGE_COLOR_YELLOW = "warning";
    private static final String SLACK_MESSAGE_COLOR_RED = "danger";

    private static final String SLACK_MESSAGE_TEMPLATE = "slack-incoming-message.ftl";

    private static final String TRIGGER_START = "start";
    private static final String TRIGGER_SUCCESS = "success";
    private static final String TRIGGER_FAILURE = "failure";

    private static final Map<String, SlackNotificationData> TRIGGER_NOTIFICATION_DATA = new TreeMap<>();

    private static final Configuration FREEMARKER_CFG = new Configuration();

    @PluginProperty(title = "WebHook URL", description = "The Slack incoming webhook URL to send notifications to",
                    required = true, scope = PropertyScope.Project)
    private String webhook_url;

    public SlackNotificationPlugin() {
        ClassTemplateLoader builtInTemplate = new ClassTemplateLoader(SlackNotificationPlugin.class, "/templates");
        TemplateLoader[] loaders = new TemplateLoader[]{builtInTemplate};
        MultiTemplateLoader mtl = new MultiTemplateLoader(loaders);
        FREEMARKER_CFG.setTemplateLoader(mtl);
        try {
            FREEMARKER_CFG.setSetting(Configuration.CACHE_STORAGE_KEY, "strong:20, soft:250");
        } catch(Configurable.UnknownSettingException e) {
            System.err.printf("Unknown FreeMarker setting '%s", Configuration.CACHE_STORAGE_KEY);
        } catch(TemplateException e) {
            System.err.printf("Failed to set FreeMarker setting '%s'. Error: %s", Configuration.CACHE_STORAGE_KEY, e.getMessage());
        }

        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_START,   new SlackNotificationData(SLACK_MESSAGE_TEMPLATE, SLACK_MESSAGE_COLOR_YELLOW));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_SUCCESS, new SlackNotificationData(SLACK_MESSAGE_TEMPLATE, SLACK_MESSAGE_COLOR_GREEN));
        TRIGGER_NOTIFICATION_DATA.put(TRIGGER_FAILURE, new SlackNotificationData(SLACK_MESSAGE_TEMPLATE, SLACK_MESSAGE_COLOR_RED));
    }


    /**
     * Sends a message to a Slack room when a job notification event is raised by Rundeck.
     *
     * @param trigger name of job notification event causing notification
     * @param executionData job execution data
     * @param config plugin configuration
     * @throws SlackNotificationPluginException when any error occurs sending the Slack message
     * @return true, if the Slack API response indicates a message was successfully delivered to a chat room
     */
    public boolean postNotification(String trigger, Map executionData, Map config) {
        if (!TRIGGER_NOTIFICATION_DATA.containsKey(trigger)) {
            throw new IllegalArgumentException("Unknown trigger type: [" + trigger + "].");
        }

        String message = generateMessage(trigger, executionData, config);
        String slackResponse = invokeSlackAPIMethod(webhook_url, message);

        if ("ok".equals(slackResponse)) {
            return true;
        } else {
            // Unfortunately there seems to be no way to obtain a reference to the plugin logger within notification plugins,
            // but throwing an exception will result in its message being logged.
            String ms = "payload=" + urlEncode(message);
            throw new SlackNotificationPluginException("Unknown status returned from Slack API: [" + slackResponse + "]." + "\n" + ms);
        }
    }

    // private String generateMessage(String trigger, Map executionData, Map config, String channel) {
    private String generateMessage(String trigger, Map executionData, Map config) {
        String templateName = TRIGGER_NOTIFICATION_DATA.get(trigger).template;
        String color = TRIGGER_NOTIFICATION_DATA.get(trigger).color;

        HashMap<String, Object> model = new HashMap<>();
        model.put("trigger", trigger);
        model.put("color", color);
        model.put("executionData", executionData);
        model.put("config", config);
        StringWriter sw = new StringWriter();
        try {
            Template template = FREEMARKER_CFG.getTemplate(templateName);
            template.process(model,sw);
        } catch (TemplateException | IOException e) {
            throw new SlackNotificationPluginException("Error merging Slack notification message template: [" + e.getMessage() + "].", e);
        }

        return sw.toString();
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new SlackNotificationPluginException("URL encoding error: [" + e.getMessage() + "].", e);
        }
    }

    // private String invokeSlackAPIMethod(String teamDomain, String token, String message) {
    private String invokeSlackAPIMethod(String webhook_url, String message) {
        // URL requestUrl = toURL(SLACK_API_URL_SCHEMA + teamDomain + SLACK_API_BASE + SLACK_API_WEHOOK_PATH + token);
        URL requestUrl = toURL(webhook_url);

        HttpURLConnection connection = null;
        InputStream responseStream = null;
        String body = "payload=" + urlEncode(message);
        try {
            connection = openConnection(requestUrl);
            putRequestStream(connection, body);
            responseStream = getResponseStream(connection);
            return getSlackResponse(responseStream);

        } finally {
            closeQuietly(responseStream);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private URL toURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException malformedURLEx) {
            throw new SlackNotificationPluginException("Slack API URL is malformed: [" + malformedURLEx.getMessage() + "].", malformedURLEx);
        }
    }

    private HttpURLConnection openConnection(URL requestUrl) {
        try {
            return (HttpURLConnection) requestUrl.openConnection();
        } catch (IOException ioEx) {
            throw new SlackNotificationPluginException("Error opening connection to Slack URL: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private void putRequestStream(HttpURLConnection connection, String message) {
        try {
            connection.setRequestMethod("POST");
//            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("charset", "utf-8");

            connection.setDoInput(true);
            connection.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
            wr.writeBytes(message);
            wr.flush();
            wr.close();
        } catch (IOException ioEx) {
            throw new SlackNotificationPluginException("Error putting data to Slack URL: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private InputStream getResponseStream(HttpURLConnection connection) {
        InputStream input;
        try {
            input = connection.getInputStream();
        } catch (IOException ioEx) {
            input = connection.getErrorStream();
        }
        return input;
    }

    private String getSlackResponse(InputStream responseStream) {
        try {
            return new Scanner(responseStream,"UTF-8").useDelimiter("\\A").next();
        } catch (Exception ioEx) {
            throw new SlackNotificationPluginException("Error reading Slack API JSON response: [" + ioEx.getMessage() + "].", ioEx);
        }
    }

    private void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ioEx) {
                // ignore
            }
        }
    }

    private static class SlackNotificationData {
        private String template;
        private String color;
        private SlackNotificationData(String template, String color) {
            this.color = color;
            this.template = template;
        }
    }

}
