package io.aawireless.wirelessaamanipulator.helpers.messageprocessors;


import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.util.Log;

import java.util.List;

import androidauto.ev.proto.Ev;
import io.aawireless.wirelessaamanipulator.R;
import io.aawireless.wirelessaamanipulator.helpers.AAMessage;


// ev.proto package (as you pasted)
import io.aawireless.wirelessaamanipulator.helpers.energymodel.EnergyModel;
import io.aawireless.wirelessaamanipulator.helpers.energymodel.EnergyModelFromJson;
import io.aawireless.wirelessaamanipulator.helpers.weather.OutsideTempService;
import io.aawireless.wirelessaamanipulator.proto.*;



public final class EnergyModelTranslator {

    public static Integer sensorChannel = null;
    private static int counter;
    public static int sensorIdx;
    private boolean hasFuelSensor;

    public EnergyModelTranslator(Context context) {
        this.context = context;
    }

    private Context context;

    // We only translate responses if we previously converted an EnergyModel request into Fuel


    public AAMessage process(AAMessage in) {
        try {
            // 1) Patch ServiceDiscoveryResponse and capture sensor channel
            if (isServiceDiscoveryResponse(in)) {
                return patchServiceDiscoveryResponse(in,context);
                //return in;
            }

            // Nothing to do until we know the sensor channel
            if (sensorChannel == null) return in;

            // Only act on messages on sensor channel
            if ((in.getChannel() & 0xFF) != (sensorChannel & 0xFF)) return in;


            //Log.d("EnergyModelTranslator","Processing message: "+in.getMessageID());

            // 2) SensorRequest handling
            if (in.getMessageID() == SensorMessageId.SENSOR_MESSAGE_REQUEST_VALUE
                    && in.channel==sensorChannel) {

                return handleSensorRequest(in);
            }

            // 2) SensorRequest handling
            if (in.getMessageID() == SensorMessageId.SENSOR_MESSAGE_RESPONSE_VALUE
                    && in.channel==sensorChannel) {

                    SensorResponse resp=SensorResponse.parseFrom(in.getData());
                    Log.d("EnergyModelTranslator","Sensor response from car: "+ resp);

            }

            // 3) SensorResponse handling
            if (in.getMessageID() == SensorMessageId.SENSOR_MESSAGE_BATCH_VALUE
                    && in.channel==sensorChannel) {

                return handleSensorResponse(in);
            }

            return in;
        } catch (Throwable t) {
            // fail-safe: do not break AA
            return in;
        }
    }

    // ----------------------------
    // ServiceDiscoveryResponse patch
    // ----------------------------

    private boolean isServiceDiscoveryResponse(AAMessage msg) {
        return (msg.getChannel() & 0xFF) == 0
                && msg.getMessageID() == ControlMessageType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE;
    }

    private AAMessage patchServiceDiscoveryResponse(AAMessage msg, Context context) throws Exception {
        ServiceDiscoveryResponse sdr = ServiceDiscoveryResponse.parseFrom(msg.getData());
        ServiceDiscoveryResponse.Builder sdrB = sdr.toBuilder();

        // A new SDR = a new car session. Drop anything learned from a previous car
        // (static state survives across connections in the same process) BEFORE we
        // decide what to strip / which channel is the sensor channel.
        sensorChannel = null;
        io.aawireless.wirelessaamanipulator.hud.ClusterInfo.reset();

        // Learn the car's instrument-cluster + display capabilities. This decides
        // whether the police-HUD compass can be pushed as a bitmap (IMAGE) or needs
        // the heavier video-sink route (ENUM). Never break AA if parsing surprises us.
        try {
            captureClusterInfo(sdr);
        } catch (Throwable t) {
            Log.w("EnergyModelTranslator", "captureClusterInfo failed (non-fatal)", t);
        }

        sensorIdx = -1;

        // Locate the service that contains sensor_source_service.sensors (aka "sensor batch" list)
        for (int i = 0; i < sdrB.getServicesCount(); i++) {
            Service svc = sdrB.getServices(i);
            if (svc.hasSensorSourceService()
                    && svc.getSensorSourceService().getSensorsCount() > 0) {
                sensorIdx = i;
                break;
            }
        }

        if (sensorIdx < 0) {
            // If the car/phone doesn't advertise sensors at all, we can't inject EV sensor cleanly.
            // Keep SDR unchanged.
            return msg;
        }

        Service.Builder svcB = sdrB.getServices(sensorIdx).toBuilder();
        SensorSourceService.Builder ssB = svcB.getSensorSourceService().toBuilder();

        // Save sensor channel ID (service id)
        sensorChannel = svcB.getId();

        // Add one more sensor of type SENSOR_VEHICLE_ENERGY_MODEL_DATA if missing
        hasFuelSensor = false;
        for (SensorSourceService.Sensor s : ssB.getSensorsList()) {
            if (s.getSensorType() == SensorType.SENSOR_FUEL) {
                hasFuelSensor = true;
                break;
            }
        }

        if (!hasFuelSensor) {
            SensorSourceService.Sensor em = SensorSourceService.Sensor.newBuilder()
                    .setSensorType(SensorType.SENSOR_FUEL)
                    .build();
            ssB.addSensors(em);

            ssB.addSupportedFuelTypes(FuelType.FUEL_TYPE_ELECTRIC);
            ssB.addAllSupportedEvConnectorTypes(List.of(new EvConnectorType[]{EvConnectorType.EV_CONNECTOR_TYPE_MENNEKES, EvConnectorType.EV_CONNECTOR_TYPE_COMBO_2}));

            svcB.setSensorSourceService(ssB.build());
            sdrB.setServices(sensorIdx, svcB.build());



            msg.setData(sdrB.build().toByteArray());
            Log.d("EnergyModelTranslator", "Added Fuel sensor: "+sdrB.build());
        }

        boolean hasEnergyModel = false;
        for (SensorSourceService.Sensor s : ssB.getSensorsList()) {
            if (s.getSensorType() == SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA) {
                hasEnergyModel = true;
                break;
            }
        }

        if (!hasEnergyModel) {
            SensorSourceService.Sensor em = SensorSourceService.Sensor.newBuilder()
                    .setSensorType(SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA)
                    .build();
            ssB.addSensors(em);

            svcB.setSensorSourceService(ssB.build());
            sdrB.setServices(sensorIdx, svcB.build());

            msg.setData(sdrB.build().toByteArray());
            Log.d("EnergyModelTranslator", "Added EnergyModel sensor: "+sdrB.build());
        }

       // EnergyModel.setup(145.33f,2.47952f,0.67997f,2520,189,48.3f,22.4f,6.3f,13.8f,28.9f,-10,0.26f);
        //EnergyModel.setup(109, 145.33f,2.47952f,0.67997f,2520,189,0,0,0,0,28.9f,0,0.26f); //Lotus static - 28.9kwh/100 at 0C
        //EnergyModel.setup(96,130.62f,2.22861f,0.61163f,2265,212,0,0,0,0,28.9f,0,0.26f); // Plaid - 26.2 khwh/100 at 0C
        //EnergyModel.setup(77,92f,1.9f,0.41f,1919,91,0,0,0,0,28.9f,0,0.23f); // Model 3 - 22.3 kw/h
        int profileRes = io.aawireless.wirelessaamanipulator.helpers.energymodel.EnergyProfiles.selectedRawRes(context);
        EnergyModelFromJson.loadFromRaw(context, profileRes,
                context.getSharedPreferences("AA_SETTINGS", MODE_PRIVATE).getInt("offset_value", 0));

        // We own the cluster: strip its sink+input from the SDR gearhead sees so gearhead
        // never opens channel 21. Otherwise it fights us for it. We then drive the whole
        // A/V handshake ourselves (ClusterVideoSession), including the VideoFocusRequest
        // the car waits for before it will show projected video.
        stripClusterFromSdr(sdrB);
        msg.setData(sdrB.build().toByteArray());
        return msg; // already contains EnergyModel sensor
    }

    /** Remove the cluster video sink + paired input from the SDR forwarded to gearhead. */
    private void stripClusterFromSdr(ServiceDiscoveryResponse.Builder sdrB) {
        if (!io.aawireless.wirelessaamanipulator.hud.HudConfig.ownCluster) return;
        int sinkId = io.aawireless.wirelessaamanipulator.hud.ClusterInfo.getVideoClusterServiceId();
        int inputId = io.aawireless.wirelessaamanipulator.hud.ClusterInfo.getVideoClusterInputServiceId();
        if (sinkId < 0) return;
        for (int i = sdrB.getServicesCount() - 1; i >= 0; i--) {
            int id = sdrB.getServices(i).getId();
            if (id == sinkId || (inputId >= 0 && id == inputId)) {
                sdrB.removeServices(i);
                Log.d("EnergyModelTranslator", "Stripped cluster service #" + id + " from SDR to gearhead");
            }
        }
    }

    /**
     * Record the car's instrument-cluster (NavigationStatusService) and any video
     * displays (MediaSinkService) from the SDR into {@link io.aawireless.wirelessaamanipulator.hud.ClusterInfo}.
     * Read-only: this does NOT modify the SDR.
     */
    private void captureClusterInfo(ServiceDiscoveryResponse sdr) {
        StringBuilder video = new StringBuilder();
        int clusterDisplayId = Integer.MIN_VALUE;
        for (int i = 0; i < sdr.getServicesCount(); i++) {
            Service svc = sdr.getServices(i);

            if (svc.hasNavigationStatusService()) {
                NavigationStatusService nav = svc.getNavigationStatusService();
                boolean image = nav.getType() == NavigationStatusService.InstrumentClusterType.IMAGE;
                if (image && nav.hasImageOptions()) {
                    NavigationStatusService.ImageOptions opts = nav.getImageOptions();
                    io.aawireless.wirelessaamanipulator.hud.ClusterInfo.setNavStatus(
                            svc.getId(), true, nav.getMinimumIntervalMs(),
                            opts.getWidth(), opts.getHeight(), opts.getColourDepthBits());
                } else {
                    io.aawireless.wirelessaamanipulator.hud.ClusterInfo.setNavStatusEnumOnly(
                            svc.getId(), nav.getMinimumIntervalMs());
                }
            }

            if (svc.hasMediaSinkService()) {
                MediaSinkService sink = svc.getMediaSinkService();
                if (video.length() > 0) video.append("; ");
                video.append("svc#").append(svc.getId())
                        .append(" displayType=").append(sink.getDisplayType())
                        .append(" displayId=").append(sink.getDisplayId())
                        .append(" videoConfigs=").append(sink.getVideoConfigsCount());

                boolean isClusterOrHud = sink.getDisplayType() == DisplayType.DISPLAY_TYPE_CLUSTER
                        || sink.getDisplayType() == DisplayType.DISPLAY_TYPE_AUXILIARY;
                // Prefer DISPLAY_TYPE_CLUSTER when both exist, but accept DISPLAY_TYPE_AUXILIARY when the
                // car routes its secondary navigation projection to the windshield HUD. A real CLUSTER
                // can still override a previously-taken AUXILIARY (canTakeSink), not the other way round.
                boolean canTakeSink = clusterDisplayId == Integer.MIN_VALUE
                        || sink.getDisplayType() == DisplayType.DISPLAY_TYPE_CLUSTER;
                if (isClusterOrHud && canTakeSink
                        && sink.getVideoConfigsCount() > 0) {
                    // Record EVERY config the car offers, indexed exactly like its list (the
                    // car's Config/Start refer to these positions), and remember which ones we
                    // can encode. Resolution + safe area then follow whatever the car picks.
                    int n = sink.getVideoConfigsCount();
                    int[][] cfgs = new int[n][];
                    int firstUsable = -1;
                    for (int c = 0; c < n; c++) {
                        VideoConfiguration vc = sink.getVideoConfigs(c);
                        int[] wh = resolutionToWh(vc.getCodecResolution());

                        // Safe drawable area = codec margins + content insets.
                        int mL = 0, mT = 0, mR = vc.getWidthMargin(), mB = vc.getHeightMargin();
                        int iL = 0, iT = 0, iR = 0, iB = 0;
                        if (vc.hasUiConfig()) {
                            UiConfig ui = vc.getUiConfig();
                            if (ui.hasMargins()) {
                                Insets m = ui.getMargins();
                                mL = m.getLeft(); mT = m.getTop(); mR = m.getRight(); mB = m.getBottom();
                            }
                            if (ui.hasContentInsets()) {
                                Insets ci = ui.getContentInsets();
                                iL = ci.getLeft(); iT = ci.getTop(); iR = ci.getRight(); iB = ci.getBottom();
                            }
                        }
                        // We only encode H.264 Baseline; an absent codec type means H.264 BP.
                        boolean usable = !vc.hasVideoCodecType()
                                || vc.getVideoCodecType() == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP;
                        cfgs[c] = new int[]{wh[0], wh[1], mL + iL, mT + iT, mR + iR, mB + iB, usable ? 1 : 0};
                        if (usable && firstUsable < 0) firstUsable = c;
                    }

                    if (firstUsable < 0) {
                        // Never take the cluster away from gearhead if we can't drive it.
                        Log.w("EnergyModelTranslator", "cluster sink svc#" + svc.getId()
                                + " offers no H.264 Baseline config -> leaving it to Android Auto");
                    } else {
                        clusterDisplayId = sink.getDisplayId();
                        io.aawireless.wirelessaamanipulator.hud.ClusterInfo.setVideoCluster(
                                svc.getId(), cfgs[firstUsable][0], cfgs[firstUsable][1]);
                        io.aawireless.wirelessaamanipulator.hud.ClusterInfo.setVideoClusterConfigs(cfgs);
                        io.aawireless.wirelessaamanipulator.hud.ClusterInfo.selectVideoClusterConfig(firstUsable);
                    }
                }
            }
        }

        // The input service paired with the cluster display (same display_id).
        if (clusterDisplayId != Integer.MIN_VALUE) {
            for (int i = 0; i < sdr.getServicesCount(); i++) {
                Service svc = sdr.getServices(i);
                if (svc.hasInputSourceService()
                        && svc.getInputSourceService().getDisplayId() == clusterDisplayId) {
                    io.aawireless.wirelessaamanipulator.hud.ClusterInfo.setVideoClusterInput(svc.getId());
                }
            }
        }

        // Highest service id gearhead will still open (anything except the cluster sink +
        // input we strip). We hold our cluster channel-open until the car has answered the
        // open for THIS channel, so we never inject in the middle of gearhead's setup.
        // Must be the max over ALL remaining ids, not just those below the sink: on the Zeekr
        // the cluster happens to be the top id, on other cars it can sit mid-range. If
        // gearhead never opens that service, ClusterVideoSession's quiet-period fallback fires.
        int sinkId = io.aawireless.wirelessaamanipulator.hud.ClusterInfo.getVideoClusterServiceId();
        int inputId = io.aawireless.wirelessaamanipulator.hud.ClusterInfo.getVideoClusterInputServiceId();
        int trigger = -1;
        if (sinkId > 0) {
            for (int i = 0; i < sdr.getServicesCount(); i++) {
                int id = sdr.getServices(i).getId();
                if (id == sinkId || id == inputId) continue;   // skip the ones we strip
                if (id > trigger) trigger = id;                 // last channel gearhead opens
            }
        }
        io.aawireless.wirelessaamanipulator.hud.ClusterInfo.setOpenTriggerChannelId(trigger);

        io.aawireless.wirelessaamanipulator.hud.ClusterInfo.setVideoDisplays(video.toString());
        Log.d("EnergyModelTranslator", "cluster openTriggerChannelId=" + trigger + "; "
                + io.aawireless.wirelessaamanipulator.hud.ClusterInfo.summary());
    }

    /** Parse a VideoCodecResolutionType like VIDEO_800x480 into {width,height}. */
    private static int[] resolutionToWh(Object resolutionEnum) {
        String n = String.valueOf(resolutionEnum);          // e.g. "VIDEO_800x480"
        try {
            String[] p = n.replace("VIDEO_", "").split("x");
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (Exception e) {
            return new int[]{800, 480}; // sane default
        }
    }

    // ----------------------------
    // SensorRequest: EnergyModel -> Fuel
    // ----------------------------

    private AAMessage handleSensorRequest(AAMessage msg) throws Exception {
        SensorRequest req = SensorRequest.parseFrom(msg.getData());


        Log.d("EnergyModelTranslator","Sensor request from phone: "+ req);

        if (req.getType() != SensorType.SENSOR_VEHICLE_ENERGY_MODEL_DATA) {
            return msg;
        }

        // To get a percentage, we need the other side's FUEL response.
        // So translate the request into FUEL.
        SensorRequest fuelReq = req.toBuilder()
                .setType(SensorType.SENSOR_FUEL)
                .build();

        msg.setData(fuelReq.toByteArray());
        return msg;
    }

    // ----------------------------
    // SensorResponse: Fuel -> EnergyModel
    // ----------------------------

    private AAMessage handleSensorResponse(AAMessage msg) throws Exception {
        SensorBatch resp = SensorBatch.parseFrom(msg.getData());




        if (resp.getLocationDataCount() >0 ){
            LocationData loc = resp.getLocationData(0);
            OutsideTempService.updateLocation(loc.getLatitudeE7()/1e7,loc.getLongitudeE7()/1e7);

            // Feed the HUD location store from the car's own GPS stream so we never
            // have to wake the phone's FusedLocationProvider (battery/heat).
            io.aawireless.wirelessaamanipulator.hud.CarLocationStore.update(
                    loc.getLatitudeE7(), loc.getLongitudeE7(),
                    loc.getBearingE6(), loc.getSpeedE3(),
                    loc.hasBearingE6(), loc.hasSpeedE3());
        }
        if (resp.getFuelDataCount() == 0) {
            return msg;
        }



        // Extract fuel % (this is the only schema-dependent part)
        // Pick the one that matches your generated proto:

        Integer percent = resp.getFuelData(0).getFuelLevel();

        // Pattern A: response has fuel_level field (int)
        // if (resp.hasFuelLevel()) percent = resp.getFuelLevel();

        // Pattern B: response has FuelData inside
        // if (resp.hasFuelData() && resp.getFuelData().hasFuelLevel()) percent = resp.getFuelData().getFuelLevel();

        // Pattern C: response has repeated FuelData
        // if (resp.getFuelDataCount() > 0 && resp.getFuelData(0).hasFuelLevel()) percent = resp.getFuelData(0).getFuelLevel();

        if (percent == null) {
            //return msg; // can't translate without percentage
            percent=90; //set dummy car battery level....
        }

        // Build EnergyModel using your builder
        Ev.EnergyModel model = EnergyModelFromJson.create(percent);



        // Now rewrite response: type -> ENERGY_MODEL, and attach model
        // This is schema-dependent too. Common patterns:
        SensorBatch mod = resp.toBuilder().addEnergyModel(model).build();

        msg.setData(mod.toByteArray());

        return msg;
    }

}
