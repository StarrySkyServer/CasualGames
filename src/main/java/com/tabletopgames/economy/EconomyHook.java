package com.tabletopgames.economy;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 经济插件（EconomyAPI）接入层。
 * <p>
 * EconomyAPI 是本插件的强依赖（见 plugin.yml 的 {@code depend}），缺失时 Nukkit 不会加载本插件，
 * 因此这里不再做「依赖缺失时降级」的处理。用反射访问是为了不与某个具体版本绑定，
 * 只在首次调用时解析一次，之后复用缓存的 {@link Method}。
 * <p>
 * 统一使用 UUID 重载，这样即使玩家在结算前离线也能正常退款/发奖。
 */
public final class EconomyHook {

    private static final String PLUGIN_NAME = "EconomyAPI";
    private static final String API_CLASS = "me.onebone.economyapi.EconomyAPI";

    /** EconomyAPI.RET_SUCCESS 的取值。 */
    private static final int RET_SUCCESS = 1;

    private static Object api;
    private static Method myMoney;
    private static Method reduceMoney;
    private static Method addMoney;

    private EconomyHook() {
    }

    /**
     * 解析 EconomyAPI。只在成功时缓存，避免把「经济插件尚未启用」误记成永久失败。
     * 依赖已由 plugin.yml 保证，解析失败只可能是接口不兼容，故打印一次严重日志便于排查。
     */
    private static boolean resolve() {
        if (api != null) {
            return true;
        }
        Plugin plugin = Server.getInstance().getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName(API_CLASS);
            Object instance = clazz.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return false;
            }
            myMoney = clazz.getMethod("myMoney", UUID.class);
            reduceMoney = clazz.getMethod("reduceMoney", UUID.class, double.class);
            addMoney = clazz.getMethod("addMoney", UUID.class, double.class);
            api = instance;
        } catch (ReflectiveOperationException | LinkageError e) {
            Server.getInstance().getLogger().critical("无法解析 EconomyAPI 接口，赌博模式将无法结算", e);
            return false;
        }
        return true;
    }

    /** 玩家当前余额。 */
    public static double balance(UUID id) {
        if (!resolve()) {
            return 0.0;
        }
        try {
            return (double) myMoney.invoke(api, id);
        } catch (ReflectiveOperationException e) {
            return 0.0;
        }
    }

    /** 扣款；成功返回 true。 */
    public static boolean reduce(UUID id, double amount) {
        if (!resolve() || amount <= 0) {
            return false;
        }
        try {
            return (int) reduceMoney.invoke(api, id, amount) == RET_SUCCESS;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** 入账；成功返回 true。 */
    public static boolean add(UUID id, double amount) {
        if (!resolve() || amount <= 0) {
            return false;
        }
        try {
            return (int) addMoney.invoke(api, id, amount) == RET_SUCCESS;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}