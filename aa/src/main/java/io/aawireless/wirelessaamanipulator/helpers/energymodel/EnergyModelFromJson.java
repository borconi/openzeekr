package io.aawireless.wirelessaamanipulator.helpers.energymodel;

import android.content.Context;
import android.util.Log;

import com.google.protobuf.ByteString;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import androidauto.ev.proto.Ev;
import io.aawireless.wirelessaamanipulator.helpers.weather.OutsideTempService;

public class EnergyModelFromJson {

    public static final long HVAC_POWER_ON   = 1L << 0;
    public static final long HVAC_AC_ON      = 1L << 1;
    public static final long HVAC_MAX_AC_ON  = 1L << 2;
    public static final long HVAC_DEFROST_ON = 1L << 3;

    private static final ByteString EMPTY_BYTES = ByteString.EMPTY;

    private static JSONObject rootJson;
    private static JSONObject dataJson;

    /**
     * Signed percentage tuning offset.
     * Range is clamped to [-10, 10].
     *
     * Negative => harder / less optimistic
     * Positive => easier / more optimistic
     */
    private static int seekOffsetPercent = 0;

    public static void setSeekOffsetPercent(int value) {
        if (value < -10) value = -10;
        if (value > 10) value = 10;
        seekOffsetPercent = value;
    }

    public static int getSeekOffsetPercent() {
        return seekOffsetPercent;
    }

    public static void loadFromJsonString(String json) throws JSONException {
        rootJson = new JSONObject(json);
        dataJson = rootJson.getJSONObject("data");
    }

    public static void loadFromJsonString(String json, int seekOffsetPercent) throws JSONException {
        setSeekOffsetPercent(seekOffsetPercent);
        loadFromJsonString(json);
    }

    public static void loadFromRaw(Context context, int rawResId) throws IOException, JSONException {
        InputStream is = context.getResources().openRawResource(rawResId);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        is.close();

        String json = buffer.toString("UTF-8");
        loadFromJsonString(json);
    }

    public static void loadFromRaw(Context context, int rawResId, int seekOffsetPercent) throws IOException, JSONException {
        Log.d("EnergyModelFromJson", "seekOffsetPercent: "+seekOffsetPercent);
        setSeekOffsetPercent(seekOffsetPercent);
        loadFromRaw(context, rawResId);
    }

    /**
     * Builds an Ev.EnergyModel from the previously loaded JSON.
     *
     * socPercentNullable:
     * - if null, batteryLevelWh from JSON is used
     * - otherwise batteryLevelWh is recalculated from batteryCapacityWh * soc / 100
     */
    public static Ev.EnergyModel create(Integer socPercentNullable) throws JSONException {
        ensureLoaded();

        JSONObject vehicleInfo = dataJson.getJSONObject("vehicleInfo");
        long batteryCapacityWh = getLongFromMean(vehicleInfo.getJSONObject("batteryCapacityWh"));

        long batteryLevelWh;
        if (socPercentNullable == null) {
            batteryLevelWh = getLongFromMean(vehicleInfo.getJSONObject("batteryLevelWh"));
        } else {
            int soc = Math.max(0, Math.min(100, socPercentNullable));
            batteryLevelWh = (batteryCapacityWh * soc) / 100L;
        }

        float outsideTemp = OutsideTempService.getTempC();
        float airDensity = OutsideTempService.getAirPressure();

        return Ev.EnergyModel.newBuilder()
                .setVehicleInfo(buildVehicleInfo(batteryLevelWh, batteryCapacityWh, outsideTemp, airDensity))
                .setRoadLoad(buildRoadLoad())
                .setDrivetrainLosses(buildDrivetrainLosses())
                .setMaxRegenPower(buildMaxRegenPower())
                .setBatteryLosses(buildBatteryLosses())
                .addAllAuxiliaryDrainAtTemp(buildAuxDrainList())
                .setChargingCurves(buildChargingCurves())
                .build();
    }

    private static void ensureLoaded() {
        if (dataJson == null) {
            throw new IllegalStateException("Energy model JSON not loaded. Call loadFromRaw(...) or loadFromJsonString(...) first.");
        }
    }

    private static Ev.VehicleInfo buildVehicleInfo(long batteryLevelWh, long batteryCapacityWh, float outsideTemp, float airDensity) throws JSONException {
        JSONObject src = dataJson.getJSONObject("vehicleInfo");

        return Ev.VehicleInfo.newBuilder()
                .setMassKilograms(intUnc(getLongFromMean(src.getJSONObject("massKilograms"))))
                .setBatteryLevelWh(intUnc(batteryLevelWh))
                .setBatteryCapacityWh(intUnc(batteryCapacityWh))
                .setReferenceAirDensity(airDensity)
                .setHvacStatus(HVAC_MAX_AC_ON)
                .setExternalTempCelsius(outsideTemp)
                .build();
    }

    private static Ev.RoadLoad buildRoadLoad() throws JSONException {
        JSONObject src = dataJson.getJSONObject("roadLoad");

        float constant = getFloatFromMean(src.getJSONObject("constant"));
        float linear = getFloatFromMean(src.getJSONObject("linear"));
        float quadratic = getFloatFromMean(src.getJSONObject("quadratic"));

        // Apply tuning only to associated values, not to breakpoints.
        constant = scaleCommon(constant);
        linear = scaleCommon(linear);

        // Quadratic gets exponential scaling because its penalty is exponential.
        quadratic = scaleQuadratic(quadratic);

        return Ev.RoadLoad.newBuilder()
                .setConstant(floatUnc(constant))
                .setLinear(floatUnc(linear))
                .setQuadratic(floatUnc(quadratic))
                .build();
    }

    private static Ev.DriveTrainLosses buildDrivetrainLosses() throws JSONException {
        JSONObject src = dataJson.getJSONObject("drivetrainLosses");
        JSONArray rows = src.getJSONArray("drivetrainPowerLoss");

        Ev.DriveTrainLosses.Builder builder = Ev.DriveTrainLosses.newBuilder();

        for (int i = 0; i < rows.length(); i++) {
            JSONObject rowObj = rows.getJSONObject(i);

            float speed = rowObj.has("speedMetersPerSecond")
                    ? getFloat(rowObj.get("speedMetersPerSecond"))
                    : 0f;

            Ev.DriveTrainPowerLoss.Builder rowBuilder = Ev.DriveTrainPowerLoss.newBuilder()
                    .setSpeedMetersPerSecond(speed);

            JSONArray lossAtPower = rowObj.getJSONArray("lossAtPower");
            for (int j = 0; j < lossAtPower.length(); j++) {
                JSONObject lp = lossAtPower.getJSONObject(j);

                long power = lp.has("powerWatts")
                        ? getLong(lp.get("powerWatts"))
                        : 0L;

                long loss = getLongFromMean(lp.getJSONObject("lossWatts"));
                loss = scaleCommon(loss);

                rowBuilder.addLossAtPower(Ev.LossAtPower.newBuilder()
                        .setPowerWatts(power)
                        .setLossWatts(intUnc(loss))
                        .build());
            }

            builder.addDrivetrainPowerLoss(rowBuilder.build());
        }

        return builder.build();
    }

    private static Ev.MaxRegenPower buildMaxRegenPower() throws JSONException {
        JSONObject src = dataJson.getJSONObject("maxRegenPower");
        JSONArray arr = src.getJSONArray("regenerationRateAtEnergyLevel");

        Ev.MaxRegenPower.Builder builder = Ev.MaxRegenPower.newBuilder();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);

            // Intentionally unchanged: do not modify points or regen curve values.
            builder.addRegenerationRateAtEnergyLevel(
                    Ev.RegenerationRateAtEnergyLevel.newBuilder()
                            .setEnergyLevelWh(getLong(obj.get("energyLevelWh")))
                            .setRegenerationRateWatts(getLong(obj.get("regenerationRateWatts")))
                            .build()
            );
        }

        return builder.build();
    }

    private static Ev.BatteryLosses buildBatteryLosses() throws JSONException {
        JSONObject src = dataJson.getJSONObject("batteryLosses");
        JSONArray arr = src.getJSONArray("batteryPowerLoss");

        Ev.BatteryLosses.Builder builder = Ev.BatteryLosses.newBuilder();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);

            long loss = getLongFromMean(obj.getJSONObject("lossWatts"));
            loss = scaleCommon(loss);

            builder.addBatteryPowerLoss(
                    Ev.BatteryPowerLoss.newBuilder()
                            .setPowerWatts(getLong(obj.get("powerWatts")))
                            .setLossWatts(intUnc(loss))
                            .build()
            );
        }

        return builder.build();
    }

    private static java.util.List<Ev.DrainAtTemp> buildAuxDrainList() throws JSONException {
        JSONArray arr = dataJson.getJSONArray("auxiliaryDrainAtTemp");
        java.util.List<Ev.DrainAtTemp> list = new java.util.ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);

            long totalDrain = getLongFromMean(obj.getJSONObject("totalDrainWatts"));
            totalDrain = scaleCommon(totalDrain);

            Ev.DrainAtTemp.Builder b = Ev.DrainAtTemp.newBuilder()
                    .setTempCelsius(getFloat(obj.get("tempCelsius")))
                    .setTotalDrainWatts(intUnc(totalDrain));

            if (hasNonEmptyValue(obj, "hvacDrainWatts")) {
                b.setHvacDrainWatts(ByteString.copyFromUtf8(String.valueOf(obj.get("hvacDrainWatts"))));
            } else {
                b.setHvacDrainWatts(EMPTY_BYTES);
            }

            if (hasNonEmptyValue(obj, "batteryConditioningDrainWatts")) {
                b.setBatteryConditioningDrainWatts(ByteString.copyFromUtf8(String.valueOf(obj.get("batteryConditioningDrainWatts"))));
            } else {
                b.setBatteryConditioningDrainWatts(EMPTY_BYTES);
            }

            list.add(b.build());
        }

        return list;
    }

    private static Ev.ChargingCurves buildChargingCurves() throws JSONException {
        JSONObject src = dataJson.getJSONObject("chargingCurves");
        JSONArray curves = src.getJSONArray("chargingCurveAtPower");

        Ev.ChargingCurves.Builder builder = Ev.ChargingCurves.newBuilder();

        for (int i = 0; i < curves.length(); i++) {
            JSONObject curveObj = curves.getJSONObject(i);

            Ev.ChargingCurveAtPower.Builder curveBuilder = Ev.ChargingCurveAtPower.newBuilder()
                    .setInputPowerWatts(getLong(curveObj.get("inputPowerWatts")));

            JSONArray timeToFullBattery = curveObj.getJSONArray("timeToFullBattery");
            for (int j = 0; j < timeToFullBattery.length(); j++) {
                JSONObject point = timeToFullBattery.getJSONObject(j);

                Ev.TimeToFullBattery.Builder pointBuilder = Ev.TimeToFullBattery.newBuilder();

                // Point axis stays unchanged.
                if (point.has("energyToFullWh")) {
                    pointBuilder.setEnergyToFullWh(getLong(point.get("energyToFullWh")));
                }

                // Associated value gets tuned.
                if (point.has("timeToFullSecs")) {
                    Object raw = point.get("timeToFullSecs");
                    String s = String.valueOf(raw);
                    if (!s.isEmpty()) {
                        long secs = getLong(raw);
                        secs = scaleCommon(secs);
                        pointBuilder.setTimeToFullSecs(secs);
                    }
                }

                curveBuilder.addTimeToFullBattery(pointBuilder.build());
            }

            builder.addChargingCurveAtPower(curveBuilder.build());
        }

        return builder.build();
    }

    private static boolean hasNonEmptyValue(JSONObject obj, String key) throws JSONException {
        if (!obj.has(key) || obj.isNull(key)) return false;
        Object v = obj.get(key);
        return !(v instanceof String && ((String) v).isEmpty());
    }

    private static long getLongFromMean(JSONObject obj) throws JSONException {
        return getLong(obj.get("mean"));
    }

    private static float getFloatFromMean(JSONObject obj) throws JSONException {
        return getFloat(obj.get("mean"));
    }

    private static long getLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return 0L;
        return Long.parseLong(s);
    }

    private static float getFloat(Object o) {
        if (o instanceof Number) return ((Number) o).floatValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return 0f;
        return Float.parseFloat(s);
    }

    /**
     * Common scale for most "cost" values.
     *
     * offset = -10 => factor 1.10  (harder)
     * offset =  10 => factor 0.90  (easier)
     */
    private static double getCommonScaleFactor() {
        return 1.0 - (seekOffsetPercent / 100.0);
    }

    /**
     * Exponential scaling for quadratic road-load coefficient.
     *
     * Uses squared common factor:
     *  -10 => 1.10^2 = 1.21
     *   10 => 0.90^2 = 0.81
     */
    private static double getQuadraticScaleFactor() {
        double s = getCommonScaleFactor();
        return s * s;
    }

    private static long scaleCommon(long value) {
        long out = Math.round(value * getCommonScaleFactor());
        return Math.max(0L, out);
    }

    private static float scaleCommon(float value) {
        float out = (float) (value * getCommonScaleFactor());
        return Math.max(0f, out);
    }

    private static float scaleQuadratic(float value) {
        float out = (float) (value * getQuadraticScaleFactor());
        return Math.max(0f, out);
    }

    private static Ev.IntWithUncertainity intUnc(long val) {
        return Ev.IntWithUncertainity.newBuilder().setMean(val).build();
    }

    private static Ev.FloatWithUncertainity floatUnc(float val) {
        return Ev.FloatWithUncertainity.newBuilder().setMean(val).build();
    }
}