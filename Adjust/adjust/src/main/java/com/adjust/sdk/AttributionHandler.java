package com.adjust.sdk;

import android.net.Uri;

import org.json.JSONObject;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.net.ssl.HttpsURLConnection;

/**
 * Created by pfms on 07/11/14.
 */
public class AttributionHandler implements IAttributionHandler {
    private CustomScheduledExecutorService scheduler;
    private IActivityHandler activityHandler;
    private ILogger logger;
    private ActivityPackage attributionPackage;
    private TimerOnce timer;
    private static final String ATTRIBUTION_TIMER_NAME = "Attribution timer";

    private boolean paused;
    private boolean hasListener;

    public URL lastUrlUsed;

    @Override
    public void teardown() {
        logger.verbose("AttributionHandler teardown");
        if (timer != null) {
            timer.cancel(true);
        }
        if (scheduler != null) {
            try {
                scheduler.shutdownNow();
            } catch(SecurityException se) {}
        }
        scheduler = null;
        activityHandler = null;
        logger = null;
        attributionPackage = null;
        timer = null;
    }

    public AttributionHandler(IActivityHandler activityHandler,
                              ActivityPackage attributionPackage,
                              boolean startsSending,
                              boolean hasListener) {
        scheduler = new CustomScheduledExecutorService("AttributionHandler");
        logger = AdjustFactory.getLogger();

        if (this.scheduler != null) {
            timer = new TimerOnce(scheduler, new Runnable() {
                @Override
                public void run() {
                    getAttributionI();
                }
            }, ATTRIBUTION_TIMER_NAME);
        } else {
            this.logger.error("Timer not initialized, attribution handler is disabled");
        }

        init(activityHandler, attributionPackage, startsSending, hasListener);
    }

    @Override
    public void init(IActivityHandler activityHandler,
                     ActivityPackage attributionPackage,
                     boolean startsSending,
                     boolean hasListener) {
        this.activityHandler = activityHandler;
        this.attributionPackage = attributionPackage;
        this.paused = !startsSending;
        this.hasListener = hasListener;
    }

    @Override
    public void getAttribution() {
        getAttribution(0);
    }

    @Override
    public void checkSessionResponse(final SessionResponseData sessionResponseData) {
        scheduler.submit(new Runnable() {
            @Override
            public void run() {
                checkSessionResponseI(sessionResponseData);
            }
        });
    }

    public void checkAttributionResponse(final AttributionResponseData attributionResponseData) {
        scheduler.submit(new Runnable() {
            @Override
            public void run() {
                checkAttributionResponseI(attributionResponseData);
            }
        });
    }

    @Override
    public void pauseSending() {
        paused = true;
    }

    @Override
    public void resumeSending() {
        paused = false;
    }

    private void getAttribution(long delayInMilliseconds) {
        // don't reset if new time is shorter than last one
        if (timer.getFireIn() > delayInMilliseconds) {
            return;
        }

        if (delayInMilliseconds != 0) {
            double waitTimeSeconds = delayInMilliseconds / 1000.0;
            String secondsString = Util.SecondsDisplayFormat.format(waitTimeSeconds);

            logger.debug("Waiting to query attribution in %s seconds", secondsString);
        }

        // set the new time the timer will fire in
        timer.startIn(delayInMilliseconds);
    }

    private void checkAttributionI(ResponseData responseData) {
        if (responseData.jsonResponse == null) {
            return;
        }

        long timerMilliseconds = responseData.jsonResponse.optLong("ask_in", -1);

        if (timerMilliseconds >= 0) {
            activityHandler.setAskingAttribution(true);

            getAttribution(timerMilliseconds);

            return;
        }
        activityHandler.setAskingAttribution(false);

        JSONObject attributionJson = responseData.jsonResponse.optJSONObject("attribution");
        responseData.attribution = AdjustAttribution.fromJson(attributionJson);
    }

    private void checkSessionResponseI(SessionResponseData sessionResponseData) {
        checkAttributionI(sessionResponseData);

        activityHandler.launchSessionResponseTasks(sessionResponseData);
    }

    private void checkAttributionResponseI(AttributionResponseData attributionResponseData) {
        checkAttributionI(attributionResponseData);

        checkDeeplinkI(attributionResponseData);

        activityHandler.launchAttributionResponseTasks(attributionResponseData);
    }

    private void checkDeeplinkI(AttributionResponseData attributionResponseData) {
        if (attributionResponseData.jsonResponse == null) {
            return;
        }

        JSONObject attributionJson = attributionResponseData.jsonResponse.optJSONObject("attribution");
        if (attributionJson == null) {
            return;
        }

        String deeplinkString = attributionJson.optString("deeplink", null);
        if (deeplinkString == null) {
            return;
        }

        attributionResponseData.deeplink = Uri.parse(deeplinkString);
    }

    private void getAttributionI() {
        if (!hasListener) {
            return;
        }

        if (paused) {
            logger.debug("Attribution handler is paused");
            return;
        }

        logger.verbose("%s", attributionPackage.getExtendedString());

        try {
            AdjustFactory.URLGetConnection urlGetConnection = Util.createGETHttpsURLConnection(
                    buildUriI(attributionPackage.getPath(), attributionPackage.getParameters()).toString(),
                    attributionPackage.getClientSdk());

            ResponseData responseData = Util.readHttpResponse(urlGetConnection.httpsURLConnection, attributionPackage);
            lastUrlUsed = urlGetConnection.url;

            if (!(responseData instanceof AttributionResponseData)) {
                return;
            }

            checkAttributionResponse((AttributionResponseData)responseData);
        } catch (Exception e) {
            logger.error("Failed to get attribution (%s)", e.getMessage());
            return;
        }
    }

    private Uri buildUriI(String path, Map<String, String> parameters) {
        Uri.Builder uriBuilder = new Uri.Builder();

        uriBuilder.scheme(Constants.SCHEME);
        uriBuilder.authority(Constants.AUTHORITY);
        uriBuilder.appendPath(path);

        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            uriBuilder.appendQueryParameter(entry.getKey(), entry.getValue());
        }

        long now = System.currentTimeMillis();
        String dateString = Util.dateFormat(now);

        uriBuilder.appendQueryParameter("sent_at", dateString);

        return uriBuilder.build();
    }
}