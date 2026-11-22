package io.aawireless.wirelessaamanipulator.helpers.energymodel;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import androidauto.ev.proto.Ev;
import io.aawireless.wirelessaamanipulator.helpers.weather.OutsideTempService;

public class EnergyModel {

    public static final long HVAC_POWER_ON   = 1L << 0;
    public static final long HVAC_AC_ON      = 1L << 1;
    public static final long HVAC_MAX_AC_ON  = 1L << 2;
    public static final long HVAC_DEFROST_ON = 1L << 3;

    private static long CAPACITY_WH = 109000L;
    private static final ByteString EMPTY_BYTES = ByteString.EMPTY;
    private static final float[] TEMP_POINTS = {-20f, 0f, 30f, 40f};

    // State maintained after setup
    private static float[] constantMap;
    private static float[] linearMap;
    private static float[] quadMap;
    private static float[] massMap;
    private static long setupMaxRegenWatts;
    private static float idealConsumptionWhPer100km;
    private static float setupDragCoefficient;

    private static final Object CACHE_LOCK = new Object();
    private static float cachedTemp = Float.NaN;
    private static long cachedMass;
    private static float cachedConstant;
    private static float cachedLinear;
    private static float cachedQuadratic;

    public static void setup(
            float battery,
            float estConstant, float estLinear, float estQuad,
            long vehicleMass, long maxRegenPower,
            float penaltyMinus20, float penalty0, float penalty30, float penalty40,
            float whPer100km, float adjuster, float dragCoefficient) {

        CAPACITY_WH= (long) (battery*1000);
        setupMaxRegenWatts = maxRegenPower;
        idealConsumptionWhPer100km = whPer100km;
        setupDragCoefficient = dragCoefficient;

        float adjBase = adjuster / 100.0f;

        // Apply non-linear adjuster logic to base values
        float baseC = estConstant * (1.0f - (adjBase * 0.7f));
        float baseL = estLinear * (1.0f - (adjBase * 0.5f));
        float baseQ = estQuad * (1.0f - (adjBase * 1.3f));

        // Constant Force Map: Rolling resistance + HVAC doubles in extreme cold
        constantMap = new float[]{
                baseC * (1 + (penaltyMinus20 * 2.20f) / 100f),
                baseC * (1 + (penalty0 * 1.60f) / 100f),
                baseC * (1 + (penalty30 * 1.05f) / 100f),
                baseC * (1 + (penalty40 * 1.20f) / 100f)
        };

        // Linear Map: Viscous mechanical losses
        linearMap = new float[]{
                baseL * (1 + (penaltyMinus20 * 1.25f) / 100f),
                baseL * (1 + (penalty0 * 1.10f) / 100f),
                baseL,
                baseL * (1 + (penalty40 * 1.05f) / 100f)
        };

        // Quadratic Map: Aero drag scaled by air density (Rho increases ~16% at -20C)
        quadMap = new float[]{
                baseQ * (1 + (penaltyMinus20 * 0.40f) / 100f),
                baseQ * (1 + (penalty0 * 0.30f) / 100f),
                baseQ,
                baseQ * (1 + (penalty40 * 0.20f) / 100f)
        };

        // Mass Scaling: Simulates range impact of cold battery internal resistance
        massMap = new float[]{
                vehicleMass * 1.30f,
                vehicleMass * 1.15f,
                (float) vehicleMass,
                (float) vehicleMass * 1.08f
        };
    }

    public static Ev.EnergyModel create(Integer socPercentNullable) {
        int soc = socPercentNullable == null ? 0 : Math.max(0, Math.min(socPercentNullable, 100));
        long levelWh = (CAPACITY_WH * (long) soc) / 100L;

        float t = OutsideTempService.getTempC();
        ensureInterpCacheUpToDate(t);

        return Ev.EnergyModel.newBuilder()
                .setVehicleInfo(buildVehicleInfo(levelWh, t))
                .setChargingCurves(getStaticCurves())
                .setRoadLoad(buildRoadLoad())
                .setMaxRegenPower(buildMaxRegenPower())
                .setBatteryLosses(buildBatteryLosses())
                .addAllAuxiliaryDrainAtTemp(buildAuxDrainList())
                .setDrivetrainLosses(buildDrivetrainLosses())
                .build();
    }

    private static void ensureInterpCacheUpToDate(float tempC) {
        synchronized (CACHE_LOCK) {
            if (!Float.isNaN(cachedTemp) && cachedTemp == tempC) return;
            cachedTemp = tempC;

            cachedMass = (long) interpolate(tempC, TEMP_POINTS, massMap);
            cachedConstant = interpolate(tempC, TEMP_POINTS, constantMap);
            cachedLinear = interpolate(tempC, TEMP_POINTS, linearMap);
            cachedQuadratic = interpolate(tempC, TEMP_POINTS, quadMap);
        }
    }

    private static Ev.VehicleInfo buildVehicleInfo(long levelWh, float t) {
        return Ev.VehicleInfo.newBuilder()
                .setMassKilograms(intUnc(cachedMass))
                .setBatteryLevelWh(intUnc(levelWh))
                .setBatteryCapacityWh(intUnc(CAPACITY_WH))
                .setReferenceAirDensity(OutsideTempService.getAirPressure())
                .setHvacStatus(HVAC_MAX_AC_ON)
                .setExternalTempCelsius(t)
                .build();
    }

    private static Ev.RoadLoad buildRoadLoad() {
        return Ev.RoadLoad.newBuilder()
                .setConstant(floatUnc(cachedConstant))
                .setLinear(floatUnc(cachedLinear))
                .setQuadratic(floatUnc(cachedQuadratic))
                .build();
    }

    private static Ev.MaxRegenPower buildMaxRegenPower() {
        Ev.MaxRegenPower.Builder builder = Ev.MaxRegenPower.newBuilder();
        for (int pct = 5; pct <= 100; pct += 5) {
            long currentLevelWh = (long) (CAPACITY_WH * (pct / 100.0));
            long allowedRegenWatts;

            if (pct <= 85) allowedRegenWatts = setupMaxRegenWatts;
            else if (pct <= 92) allowedRegenWatts = (long) (setupMaxRegenWatts * 0.70);
            else if (pct <= 97) allowedRegenWatts = (long) (setupMaxRegenWatts * 0.30);
            else allowedRegenWatts = Math.min(setupMaxRegenWatts, 15000L);

            builder.addRegenerationRateAtEnergyLevel(Ev.RegenerationRateAtEnergyLevel.newBuilder()
                    .setEnergyLevelWh(currentLevelWh)
                    .setRegenerationRateWatts(allowedRegenWatts)
                    .build());
        }
        return builder.build();
    }


    private static Ev.DriveTrainLosses buildDrivetrainLosses() {
       /* final float[] speeds = new float[] {
                0f,
                3.9903173f,
                7.9806347f,
                11.970952f,
                15.961269f,
                19.951586f,
                23.941904f,
                27.93222f,
                31.9225f,
                35.9129f
        };

        final long[] powers = new long[] {
                0L, 7816L, 15632L, 23448L, 31264L, 39082L, 46898L, 54714L, 62530L, 70346L,
                78162L, 85978L, 93796L, 101612L, 109428L, 117244L, 125060L, 132876L, 140692L, 148508L,
                156326L, 164142L, 171958L, 179774L, 187590L, 195406L, 203222L, 211040L, 218856L, 226672L,
                234488L, 242304L, 250120L, 257936L, 265752L, 273570L, 281386L, 289202L, 297018L, 304834L,
                312650L, 320466L, 328284L, 336100L, 343916L, 351732L, 359548L, 367364L, 375180L, 382996L
        };

        final long[][] losses = new long[][] {
                {450L, 695L, 1007L, 1384L, 1827L, 2334L, 2905L, 3539L, 4235L, 4993L, 5812L, 6690L, 7629L, 8626L, 9680L, 10792L, 11961L, 13185L, 14464L, 15798L, 17185L, 18625L, 20118L, 21661L, 23255L, 24900L, 26593L, 28335L, 30125L, 31962L, 33845L, 35773L, 37747L, 39765L, 41826L, 43930L, 46076L, 48263L, 50490L, 52758L, 55064L, 57409L, 59791L, 62210L, 64666L, 67157L, 69682L, 72242L, 74834L, 77460L},
                {907L, 1091L, 1335L, 1637L, 1998L, 2415L, 2889L, 3419L, 4004L, 4644L, 5336L, 6082L, 6880L, 7729L, 8629L, 9579L, 10578L, 11626L, 12721L, 13864L, 15053L, 16287L, 17566L, 18890L, 20257L, 21666L, 23118L, 24610L, 26144L, 27717L, 29329L, 30979L, 32667L, 34392L, 36153L, 37950L, 39781L, 41647L, 43545L, 45476L, 47439L, 49433L, 51457L, 53511L, 55594L, 57705L, 59843L, 62008L, 64199L, 66416L},
                {1313L, 1453L, 1645L, 1888L, 2182L, 2527L, 2920L, 3362L, 3852L, 4388L, 4971L, 5600L, 6274L, 6991L, 7752L, 8556L, 9402L, 10289L, 11216L, 12184L, 13191L, 14235L, 15318L, 16437L, 17593L, 18783L, 20009L, 21269L, 22561L, 23887L, 25244L, 26632L, 28051L, 29499L, 30976L, 32482L, 34014L, 35574L, 37159L, 38770L, 40405L, 42064L, 43747L, 45451L, 47177L, 48925L, 50692L, 52479L, 54284L, 56108L},
                {1668L, 1780L, 1936L, 2137L, 2381L, 2668L, 2996L, 3366L, 3777L, 4227L, 4716L, 5244L, 5809L, 6411L, 7050L, 7724L, 8432L, 9175L, 9951L, 10759L, 11599L, 12470L, 13372L, 14303L, 15263L, 16252L, 17267L, 18310L, 19378L, 20472L, 21590L, 22732L, 23898L, 25085L, 26294L, 27525L, 28775L, 30045L, 31333L, 32640L, 33964L, 35304L, 36660L, 38031L, 39417L, 40816L, 42229L, 43653L, 45089L, 46536L},
                {1973L, 2073L, 2210L, 2383L, 2593L, 2839L, 3119L, 3433L, 3780L, 4160L, 4572L, 5014L, 5487L, 5990L, 6522L, 7082L, 7669L, 8283L, 8923L, 9589L, 10278L, 10992L, 11729L, 12488L, 13269L, 14071L, 14893L, 15734L, 16594L, 17472L, 18368L, 19280L, 20208L, 21151L, 22108L, 23079L, 24063L, 25059L, 26067L, 27085L, 28113L, 29151L, 30197L, 31251L, 32313L, 33380L, 34454L, 35532L, 36614L, 37700L},
                {2228L, 2331L, 2465L, 2627L, 2819L, 3039L, 3287L, 3561L, 3861L, 4187L, 4537L, 4911L, 5308L, 5727L, 6168L, 6630L, 7112L, 7614L, 8134L, 8673L, 9228L, 9801L, 10389L, 10992L, 11609L, 12241L, 12885L, 13541L, 14209L, 14888L, 15576L, 16274L, 16981L, 17695L, 18417L, 19145L, 19878L, 20617L, 21359L, 22106L, 22855L, 23606L, 24358L, 25111L, 25864L, 26616L, 27366L, 28114L, 28859L, 29601L},
                {2433L, 2555L, 2701L, 2869L, 3059L, 3270L, 3501L, 3751L, 4021L, 4308L, 4612L, 4933L, 5270L, 5622L, 5989L, 6369L, 6762L, 7167L, 7584L, 8011L, 8449L, 8896L, 9351L, 9814L, 10285L, 10761L, 11244L, 11731L, 12223L, 12718L, 13216L, 13716L, 14217L, 14719L, 15221L, 15722L, 16221L, 16718L, 17212L, 17702L, 18188L, 18668L, 19143L, 19611L, 20071L, 20524L, 20967L, 21401L, 21825L, 22237L},
                {2587L, 2745L, 2920L, 3109L, 3313L, 3531L, 3761L, 4004L, 4258L, 4523L, 4798L, 5082L, 5275L, 5676L, 5984L, 6298L, 6618L, 6943L, 7272L, 7604L, 7940L, 8277L, 8616L, 8956L, 9295L, 9633L, 9970L, 10305L, 10636L, 10964L, 11287L, 11605L, 11917L, 12222L, 12520L, 12810L, 13091L, 13363L, 13624L, 13874L, 14113L, 14339L, 14551L, 14750L, 14934L, 15103L, 15256L, 15391L, 15510L, 15610L},
                {2691L, 2901L, 3120L, 3347L, 3581L, 3821L, 4067L, 4318L, 4573L, 4832L, 5093L, 5357L, 5621L, 5887L, 6153L, 6417L, 6680L, 6940L, 7199L, 7452L, 7702L, 7946L, 8184L, 8416L, 8640L, 8856L, 9063L, 9261L, 9448L, 9624L, 9789L, 9941L, 10080L, 10205L, 10315L, 10410L, 10489L, 10551L, 10596L, 10622L, 10629L, 10617L, 10584L, 10529L, 10453L, 10354L, 10232L, 10086L, 9915L, 9719L},
                {2744L, 3022L, 3302L, 3582L, 3862L, 4141L, 4419L, 4694L, 4967L, 5235L, 5499L, 5758L, 6011L, 6257L, 6496L, 6727L, 6949L, 7162L, 7364L, 7555L, 7734L, 7901L, 8055L, 8194L, 8320L, 8429L, 8523L, 8600L, 8659L, 8700L, 8722L, 8724L, 8706L, 8667L, 8605L, 8522L, 8414L, 8283L, 8127L, 7945L, 7737L, 7502L, 7239L, 6948L, 6628L, 6278L, 5897L, 5485L, 5040L, 4563L}
        };*/

        final float[] speeds = new float[] {
                0f,
                3.9903173f,
                7.9806347f,
                11.970952f,
                15.961269f,
                19.951586f,
                23.941904f,
                27.93222f,
                31.9225f,
                35.9129f
        };

        final long[] powers = new long[] {
                0L, 8489L, 16977L, 25466L, 33954L, 42445L, 50934L, 59422L, 67911L, 76400L,
                84888L, 93377L, 101867L, 110355L, 118844L, 127333L, 135821L, 144310L, 152799L, 161287L,
                169778L, 178266L, 186755L, 195244L, 203732L, 212221L, 220710L, 229201L, 237689L, 246178L,
                254667L, 263155L, 271644L, 280133L, 288621L, 297112L, 305600L, 314089L, 322578L, 331066L,
                339555L, 348044L, 356535L, 365023L, 373512L, 382000L, 390488L, 398977L, 407466L, 415954L
        };

        final long[][] losses = new long[][] {
                {317L, 540L, 824L, 1167L, 1571L, 2033L, 2553L, 3130L, 3764L, 4455L, 5201L, 6001L, 6856L, 7764L, 8724L, 9738L, 10803L, 11919L, 13085L, 14300L, 15564L, 16875L, 18234L, 19640L, 21091L, 22589L, 24130L, 25716L, 27346L, 29018L, 30733L, 32490L, 34287L, 36125L, 38003L, 39919L, 41874L, 43867L, 45896L, 47963L, 50064L, 52202L, 54372L, 56577L, 58816L, 61086L, 63388L, 65722L, 68086L, 70482L},
                {638L, 805L, 1027L, 1302L, 1631L, 2011L, 2443L, 2926L, 3459L, 4042L, 4672L, 5352L, 6078L, 6852L, 7672L, 8537L, 9448L, 10402L, 11399L, 12440L, 13523L, 14647L, 15812L, 17019L, 18264L, 19549L, 20873L, 22235L, 23636L, 25073L, 26547L, 28058L, 29604L, 31185L, 32800L, 34449L, 36130L, 37843L, 39588L, 41364L, 43170L, 45006L, 46870L, 48764L, 50685L, 52633L, 54609L, 56610L, 58638L, 60690L},
                {924L, 1052L, 1227L, 1448L, 1716L, 2030L, 2388L, 2791L, 3238L, 3726L, 4257L, 4829L, 5443L, 6095L, 6788L, 7520L, 8291L, 9100L, 9946L, 10828L, 11746L, 12697L, 13684L, 14703L, 15756L, 16840L, 17957L, 19104L, 20281L, 21489L, 22725L, 23990L, 25282L, 26601L, 27947L, 29318L, 30714L, 32135L, 33579L, 35047L, 36537L, 38050L, 39584L, 41139L, 42714L, 44309L, 45923L, 47557L, 49208L, 50878L},
                {1173L, 1275L, 1417L, 1600L, 1822L, 2084L, 2383L, 2720L, 3094L, 3503L, 3949L, 4430L, 4944L, 5492L, 6074L, 6688L, 7333L, 8010L, 8717L, 9454L, 10220L, 11016L, 11839L, 12690L, 13568L, 14472L, 15403L, 16359L, 17339L, 18344L, 19373L, 20425L, 21500L, 22597L, 23715L, 24854L, 26012L, 27191L, 28388L, 29603L, 30837L, 32088L, 33356L, 34641L, 35941L, 37257L, 38588L, 39933L, 41292L, 42664L},
                {1388L, 1479L, 1604L, 1762L, 1954L, 2178L, 2433L, 2719L, 3035L, 3382L, 3757L, 4160L, 4591L, 5050L, 5534L, 6044L, 6579L, 7138L, 7722L, 8330L, 8960L, 9614L, 10288L, 10983L, 11698L, 12431L, 13184L, 13955L, 14743L, 15549L, 16371L, 17209L, 18063L, 18932L, 19815L, 20713L, 21624L, 22549L, 23486L, 24435L, 25396L, 26368L, 27350L, 28344L, 29346L, 30359L, 31380L, 32409L, 33446L, 34490L},
                {1567L, 1661L, 1783L, 1930L, 2105L, 2306L, 2532L, 2781L, 3054L, 3351L, 3670L, 4011L, 4373L, 4754L, 5156L, 5577L, 6016L, 6474L, 6947L, 7437L, 7942L, 8464L, 8999L, 9548L, 10110L, 10686L, 11272L, 11872L, 12482L, 13102L, 13732L, 14371L, 15018L, 15674L, 16337L, 17007L, 17684L, 18368L, 19057L, 19752L, 20452L, 21157L, 21867L, 22580L, 23296L, 24015L, 24737L, 25461L, 26186L, 26913L},
                {1711L, 1822L, 1955L, 2108L, 2281L, 2473L, 2683L, 2911L, 3157L, 3418L, 3695L, 3988L, 4295L, 4616L, 4950L, 5296L, 5654L, 6023L, 6403L, 6792L, 7191L, 7598L, 8013L, 8435L, 8864L, 9297L, 9737L, 10180L, 10628L, 11078L, 11531L, 11986L, 12442L, 12900L, 13357L, 13813L, 14269L, 14723L, 15174L, 15621L, 16065L, 16505L, 16939L, 17367L, 17789L, 18204L, 18610L, 19008L, 19396L, 19773L},
                {1820L, 1964L, 2123L, 2295L, 2481L, 2679L, 2888L, 3109L, 3341L, 3583L, 3834L, 4093L, 4269L, 4634L, 4914L, 5200L, 5492L, 5788L, 6088L, 6391L, 6697L, 7004L, 7313L, 7623L, 7932L, 8240L, 8547L, 8852L, 9154L, 9453L, 9747L, 10037L, 10321L, 10598L, 10869L, 11132L, 11387L, 11633L, 11870L, 12098L, 12316L, 12522L, 12715L, 12897L, 13064L, 13218L, 13357L, 13480L, 13589L, 13680L},
                {1893L, 2084L, 2284L, 2491L, 2704L, 2923L, 3147L, 3376L, 3608L, 3844L, 4082L, 4322L, 4563L, 4805L, 5047L, 5287L, 5527L, 5764L, 5999L, 6229L, 6457L, 6679L, 6896L, 7107L, 7311L, 7507L, 7696L, 7877L, 8047L, 8208L, 8358L, 8496L, 8622L, 8736L, 8836L, 8923L, 8994L, 9050L, 9091L, 9115L, 9122L, 9111L, 9081L, 9031L, 8962L, 8872L, 8761L, 8628L, 8472L, 8294L},
                {1930L, 2183L, 2438L, 2693L, 2948L, 3202L, 3456L, 3706L, 3955L, 4199L, 4439L, 4675L, 4905L, 5129L, 5347L, 5557L, 5759L, 5953L, 6137L, 6311L, 6474L, 6626L, 6766L, 6892L, 7007L, 7105L, 7191L, 7261L, 7315L, 7352L, 7372L, 7373L, 7357L, 7321L, 7265L, 7190L, 7091L, 6972L, 6830L, 6664L, 6475L, 6261L, 6022L, 5758L, 5467L, 5149L, 4802L, 4427L, 4022L, 3587L}
        };

        Ev.DriveTrainLosses.Builder builder = Ev.DriveTrainLosses.newBuilder();

        for (int i = 0; i < speeds.length; i++) {
            Ev.DriveTrainPowerLoss.Builder row = Ev.DriveTrainPowerLoss.newBuilder()
                    .setSpeedMetersPerSecond(speeds[i]);

            for (int j = 0; j < powers.length; j++) {
                row.addLossAtPower(Ev.LossAtPower.newBuilder()
                        .setPowerWatts(powers[j])
                        .setLossWatts(intUnc(losses[i][j]))
                        .build());
            }

            builder.addDrivetrainPowerLoss(row.build());
        }

        return builder.build();
    }
    /*
    private static Ev.DriveTrainLosses buildDrivetrainLosses() {
        Ev.DriveTrainLosses.Builder builder = Ev.DriveTrainLosses.newBuilder();
        // Extended speed range to better capture motorway thermal overhead
        float[] speeds = {0f, 5f, 10f, 15f, 20f, 25f, 30f, 35f, 40f};

        for (float speed : speeds) {
            Ev.DriveTrainPowerLoss.Builder lossBuilder = Ev.DriveTrainPowerLoss.newBuilder()
                    .setSpeedMetersPerSecond(speed);
            for (int p = 0; p <= setupMaxRegenWatts; p += 20000) {
                lossBuilder.addLossAtPower(Ev.LossAtPower.newBuilder()
                        .setPowerWatts(p)
                        .setLossWatts(intUnc(estimateRealisticLoss(speed, p)))
                        .build());
            }
            builder.addDrivetrainPowerLoss(lossBuilder.build());
        }
        return builder.build();
    }
*/
    /**
     * Motorway Compensation Logic: Uses Drag Coefficient to scale drivetrain thermal
     * and inverter switching losses at higher speeds.
     */
    private static long estimateRealisticLoss(float speed, int power) {
        if (speed == 0) return 450 + (long)(power * 0.15);

        // Speed Penalty amplified by Drag Coefficient for motorway accuracy
        double speedComp = 1.0 + (setupDragCoefficient * (speed / 25.0));
        long speedLoss = (long)((950 * speedComp) + (Math.pow(speed, 2.25) * 2.0));

        // Exponential Load Penalty (Ohmic heating)
        double loadFactor = (double) power / setupMaxRegenWatts;
        long loadLoss = (long)(power * (0.18 + (Math.pow(loadFactor, 2.1) * 0.28)));

        return speedLoss + loadLoss;
    }


    private static Ev.BatteryLosses buildBatteryLosses() {
        Ev.BatteryLosses.Builder builder = Ev.BatteryLosses.newBuilder();
        for (int p = 10000; p <= setupMaxRegenWatts; p += 20000) {
            long lossMean = (long)(0.000000021 * Math.pow(p, 2));
            builder.addBatteryPowerLoss(Ev.BatteryPowerLoss.newBuilder()
                    .setPowerWatts(p)
                    .setLossWatts(intUnc(lossMean))
                    .build());
        }
        return builder.build();
    }

    private static float interpolate(float x, float[] xPoints, float[] yPoints) {
        if (x <= xPoints[0]) return yPoints[0];
        if (x >= xPoints[xPoints.length - 1]) return yPoints[yPoints.length - 1];
        for (int i = 0; i < xPoints.length - 1; i++) {
            if (x >= xPoints[i] && x <= xPoints[i + 1]) {
                float x0 = xPoints[i], x1 = xPoints[i + 1], y0 = yPoints[i], y1 = yPoints[i + 1];
                return y0 + (x - x0) * (y1 - y0) / (x1 - x0);
            }
        }
        return yPoints[0];
    }

    private static Ev.IntWithUncertainity intUnc(long val) {
        return Ev.IntWithUncertainity.newBuilder().setMean(val).build();
    }

    private static Ev.FloatWithUncertainity floatUnc(float val) {
        return Ev.FloatWithUncertainity.newBuilder().setMean(val).build();
    }
        public static Ev.ChargingCurves getStaticCurves() {
            return Ev.ChargingCurves.newBuilder()
                    .addChargingCurveAtPower(buildUltraFastDCCurve())
                    .addChargingCurveAtPower(buildStandardACCurve())
                    .build();
        }

        // Charging curve methods (buildUltraFastDCCurve, buildStandardACCurve, createPoint)


    private static Ev.ChargingCurveAtPower buildUltraFastDCCurve() {
        // High-resolution 350kW Tier
        return Ev.ChargingCurveAtPower.newBuilder()
                .setInputPowerWatts(350000L)
                // Energy levels calculated based on 109,000 Wh usable capacity
                .addTimeToFullBattery(createPoint(109000L,3071L))
                .addTimeToFullBattery(createPoint(107910L,3047L))
                .addTimeToFullBattery(createPoint(106820L,3031L))
                .addTimeToFullBattery(createPoint(105730L,3014L))
                .addTimeToFullBattery(createPoint(104640L,2997L))
                .addTimeToFullBattery(createPoint(103550L,2981L))
                .addTimeToFullBattery(createPoint(102460L,2964L))
                .addTimeToFullBattery(createPoint(101370L,2947L))
                .addTimeToFullBattery(createPoint(100280L,2931L))
                .addTimeToFullBattery(createPoint(99190L,2916L))
                .addTimeToFullBattery(createPoint(98100L,2902L))
                .addTimeToFullBattery(createPoint(97010L,2888L))
                .addTimeToFullBattery(createPoint(95920L,2874L))
                .addTimeToFullBattery(createPoint(94830L,2860L))
                .addTimeToFullBattery(createPoint(93740L,2846L))
                .addTimeToFullBattery(createPoint(92650L,2832L))
                .addTimeToFullBattery(createPoint(91560L,2818L))
                .addTimeToFullBattery(createPoint(90470L,2804L))
                .addTimeToFullBattery(createPoint(89380L,2790L))
                .addTimeToFullBattery(createPoint(88290L,2776L))
                .addTimeToFullBattery(createPoint(87200L,2762L))
                .addTimeToFullBattery(createPoint(86110L,2748L))
                .addTimeToFullBattery(createPoint(85020L,2734L))
                .addTimeToFullBattery(createPoint(83930L,2720L))
                .addTimeToFullBattery(createPoint(82840L,2707L))
                .addTimeToFullBattery(createPoint(81750L,2693L))
                .addTimeToFullBattery(createPoint(80660L,2679L))
                .addTimeToFullBattery(createPoint(79570L,2665L))
                .addTimeToFullBattery(createPoint(78480L,2651L))
                .addTimeToFullBattery(createPoint(77390L,2638L))
                .addTimeToFullBattery(createPoint(76300L,2624L))
                .addTimeToFullBattery(createPoint(75210L,2610L))
                .addTimeToFullBattery(createPoint(74120L,2595L))
                .addTimeToFullBattery(createPoint(73030L,2577L))
                .addTimeToFullBattery(createPoint(71940L,2560L))
                .addTimeToFullBattery(createPoint(70850L,2542L))
                .addTimeToFullBattery(createPoint(69760L,2525L))
                .addTimeToFullBattery(createPoint(68670L,2507L))
                .addTimeToFullBattery(createPoint(67580L,2490L))
                .addTimeToFullBattery(createPoint(66490L,2472L))
                .addTimeToFullBattery(createPoint(65400L,2455L))
                .addTimeToFullBattery(createPoint(64310L,2437L))
                .addTimeToFullBattery(createPoint(63220L,2420L))
                .addTimeToFullBattery(createPoint(62130L,2403L))
                .addTimeToFullBattery(createPoint(61040L,2385L))
                .addTimeToFullBattery(createPoint(59950L,2368L))
                .addTimeToFullBattery(createPoint(58860L,2351L))
                .addTimeToFullBattery(createPoint(57770L,2334L))
                .addTimeToFullBattery(createPoint(56680L,2316L))
                .addTimeToFullBattery(createPoint(55590L,2299L))
                .addTimeToFullBattery(createPoint(54500L,2282L))
                .addTimeToFullBattery(createPoint(53410L,2265L))
                .addTimeToFullBattery(createPoint(52320L,2248L))
                .addTimeToFullBattery(createPoint(51230L,2231L))
                .addTimeToFullBattery(createPoint(50140L,2214L))
                .addTimeToFullBattery(createPoint(49050L,2197L))
                .addTimeToFullBattery(createPoint(47960L,2181L))
                .addTimeToFullBattery(createPoint(46870L,2164L))
                .addTimeToFullBattery(createPoint(45780L,2147L))
                .addTimeToFullBattery(createPoint(44690L,2131L))
                .addTimeToFullBattery(createPoint(43600L,2113L))
                .addTimeToFullBattery(createPoint(42510L,2095L))
                .addTimeToFullBattery(createPoint(41420L,2076L))
                .addTimeToFullBattery(createPoint(40330L,2058L))
                .addTimeToFullBattery(createPoint(39240L,2039L))
                .addTimeToFullBattery(createPoint(38150L,2019L))
                .addTimeToFullBattery(createPoint(37060L,1999L))
                .addTimeToFullBattery(createPoint(35970L,1980L))
                .addTimeToFullBattery(createPoint(34880L,1960L))
                .addTimeToFullBattery(createPoint(33790L,1939L))
                .addTimeToFullBattery(createPoint(32700L,1917L))
                .addTimeToFullBattery(createPoint(31610L,1895L))
                .addTimeToFullBattery(createPoint(30520L,1872L))
                .addTimeToFullBattery(createPoint(29430L,1851L))
                .addTimeToFullBattery(createPoint(28340L,1829L))
                .addTimeToFullBattery(createPoint(27250L,1805L))
                .addTimeToFullBattery(createPoint(26160L,1780L))
                .addTimeToFullBattery(createPoint(25070L,1755L))
                .addTimeToFullBattery(createPoint(23980L,1730L))
                .addTimeToFullBattery(createPoint(22890L,1705L))
                .addTimeToFullBattery(createPoint(21800L,1680L))
                .addTimeToFullBattery(createPoint(20710L,1650L))
                .addTimeToFullBattery(createPoint(19620L,1614L))
                .addTimeToFullBattery(createPoint(18530L,1578L))
                .addTimeToFullBattery(createPoint(17440L,1541L))
                .addTimeToFullBattery(createPoint(16350L,1493L))
                .addTimeToFullBattery(createPoint(15260L,1417L))
                .addTimeToFullBattery(createPoint(14170L,1340L))
                .addTimeToFullBattery(createPoint(13080L,1263L))
                .addTimeToFullBattery(createPoint(11990L,1187L))
                .addTimeToFullBattery(createPoint(10900L,1110L))
                .addTimeToFullBattery(createPoint(9810L,1035L))
                .addTimeToFullBattery(createPoint(8720L,955L))
                .addTimeToFullBattery(createPoint(7630L,869L))
                .addTimeToFullBattery(createPoint(6540L,783L))
                .addTimeToFullBattery(createPoint(5450L,696L))
                .addTimeToFullBattery(createPoint(4360L,608L))
                .addTimeToFullBattery(createPoint(3270L,492L))
                .addTimeToFullBattery(createPoint(2180L,339L))
                .addTimeToFullBattery(createPoint(1090L,179L))
                .addTimeToFullBattery(createPoint(0L,0L))
                // 99.5% SoC

                .build();
    }

    private static Ev.ChargingCurveAtPower buildStandardACCurve() {
        // 22kW On-board Charger Tier
        return Ev.ChargingCurveAtPower.newBuilder()
                .setInputPowerWatts(22000L)
                .addTimeToFullBattery(createPoint(109000L, 17850L)) // 0% SoC (~5.8 hrs)
                .addTimeToFullBattery(createPoint(54500L, 8925L))  // 50% SoC
                .addTimeToFullBattery(createPoint(0L, 0L))          // 100% SoC
                .build();
    }

    private static Ev.TimeToFullBattery createPoint(long energyWh, long timeSec) {
        return Ev.TimeToFullBattery.newBuilder()
                .setEnergyToFullWh(energyWh)
                .setTimeToFullSecs(timeSec)
                .build();
    }


    private static List<Ev.DrainAtTemp> buildAuxDrainList() {
        List<Ev.DrainAtTemp> list = new ArrayList<>();
        //Lotus values
        //float[] temps = {-22.5f, -17.5f, -12.5f, -7.5f, -2.5f, 2.5f, 7.5f, 12.5f, 17.5f, 22.5f, 27.5f, 32.5f, 37.5f, 42.5f, 47.5f};
        //long[] drains = {6200,     6197,    6055, 5861,  5460, 4900, 4128, 3590,   2835,  2100,  2850, 3020,  3150,  3350,  3950};

        //Model 3 values
        float[] temps = {-22.5f, -17.5f, -12.5f, -7.5f, -2.5f, 2.5f, 7.5f, 12.5f, 17.5f, 22.5f, 27.5f, 32.5f, 37.5f, 42.5f, 47.5f};
        long[] drains = {4565, 4562, 4442, 4194, 3855, 3467, 3068, 2698, 2398, 2206, 2163, 2223, 2545, 3134, 4028};

        for (int i = 0; i < temps.length; i++) {
            list.add(Ev.DrainAtTemp.newBuilder()
                    .setTempCelsius(temps[i])
                    .setHvacDrainWatts(EMPTY_BYTES)
                    .setBatteryConditioningDrainWatts(EMPTY_BYTES)
                    .setTotalDrainWatts(intUnc(drains[i]))
                    .build());
        }
        return list;
    }



}