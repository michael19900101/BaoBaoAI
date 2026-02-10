package com.aotuman.baobaoai.utils;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.PrintWriter;

public class SystemCtrlUtil {

    /**
     * 判断屏幕是否锁屏
     */
    public static boolean isScreenLocked(Context context) {
        if (context == null) {
            return false;
        }
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    /**
     * 判断屏幕是否息屏
     */
    public static boolean isScreenOff(Context context) {
        if (context == null) {
            return false;
        }
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && !powerManager.isInteractive();
    }

    public static boolean unlockScreen() {
        PrintWriter printWriter = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            printWriter = new PrintWriter(process.getOutputStream());
            // 发送唤醒设备的命令
            printWriter.println("input keyevent 82"); // KEYCODE_POWER
            // 发送滑动解锁的命令（假设是从左到右滑动）
//            printWriter.println("input swipe 300 1000 800 1000");
            printWriter.flush();
            printWriter.close();
            int value = process.waitFor();
            return returnResult(value);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }

    public static boolean rootSilenceInstallApk(String apkPath){
        PrintWriter printWriter = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            printWriter = new PrintWriter(process.getOutputStream());
            printWriter.println("chmod 777 "+apkPath);
            printWriter.println("pm install -r "+apkPath);
            printWriter.flush();
            printWriter.close();
            int value = process.waitFor();
            return returnResult(value);
        } catch (Exception e) {
            e.printStackTrace();
        }finally{
            if(process!=null){
                process.destroy();
            }
        }
        return false;
    }

    public static boolean rootStartApk(String packageName, String activityName){
        boolean isSuccess = false;
        String cmd = "am start -n " + packageName + "/" + activityName + " \n";
        PrintWriter printWriter = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            printWriter = new PrintWriter(process.getOutputStream());
            printWriter.println(cmd);
            printWriter.flush();
            printWriter.close();
            int value = process.waitFor();
            return returnResult(value);
        } catch (Exception e) {
            e.printStackTrace();
        } finally{
            if(process!=null){
                process.destroy();
            }
        }
        return isSuccess;
    }

    public static boolean rootSilenceUninstallApk(String packageName){
        PrintWriter PrintWriter = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            PrintWriter = new PrintWriter(process.getOutputStream());
            PrintWriter.println("LD_LIBRARY_PATH=/vendor/lib:/system/lib ");
            PrintWriter.println("pm uninstall "+packageName);
            PrintWriter.flush();
            PrintWriter.close();
            int value = process.waitFor();
            return returnResult(value);
        } catch (Exception e) {
            e.printStackTrace();
        }finally{
            if(process!=null){
                process.destroy();
            }
        }
        return false;
    }

    public static boolean rootReboot(){
        PrintWriter PrintWriter = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            PrintWriter = new PrintWriter(process.getOutputStream());
            PrintWriter.println("reboot");
            PrintWriter.flush();
            PrintWriter.close();
            int value = process.waitFor();
            return returnResult(value);
        } catch (Exception e) {
            e.printStackTrace();
        }finally{
            if(process!=null){
                process.destroy();
            }
        }
        return false;
    }

    public static boolean sysHasRootPermission(){
        PrintWriter PrintWriter = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            PrintWriter = new PrintWriter(process.getOutputStream());
            PrintWriter.flush();
            PrintWriter.close();
            int value = process.waitFor();
            return returnResult(value);
        } catch (Exception e) {
            e.printStackTrace();
        }finally{
            if(process!=null){
                process.destroy();
            }
        }
        return false;
    }

    private static boolean returnResult(int value){
        if (value == 0) {
            return true;
        } else if (value == 1) {
            return false;
        } else {
            return false;
        }
    }

    public static boolean appHasRootPermission(Context context){
        return RootCommand("chmod 777 "+context.getPackageCodePath());
    }

    public static boolean RootCommand(String command)
    {
        Process process = null;
        DataOutputStream os = null;
        try
        {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            return returnResult(process.waitFor());
        } catch (Exception e)
        {
            Log.d("*** DEBUG ***", "ROOT REE" + e.getMessage());
            return false;
        } finally
        {
            try
            {
                if (os != null)
                {
                    os.close();
                }
                process.destroy();
            } catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    /**
     * 使用 root 权限自动启用无障碍服务
     * @param context 上下文
     * @param serviceName 无障碍服务的完整名称（格式：包名/服务类名）
     * @return 是否成功
     */
    public static boolean enableAccessibilityService(Context context, String serviceName) {
        // 先获取当前已启用的服务列表
        String currentServices = android.provider.Settings.Secure.getString(
            context.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        // 如果已经包含该服务，直接返回成功
        if (currentServices != null && currentServices.contains(serviceName)) {
            Log.d("SystemCtrlUtil", "Accessibility service already enabled: " + serviceName);
            return true;
        }

        // 构建新的服务列表
        String newServices = (currentServices == null || currentServices.isEmpty())
            ? serviceName
            : currentServices + ":" + serviceName;

        // 使用 root 权限执行 settings 命令
        boolean success = RootCommand("settings put secure enabled_accessibility_services " + newServices);

        if (success) {
            // 启用无障碍功能
            success = RootCommand("settings put secure accessibility_enabled 1");
            Log.d("SystemCtrlUtil", "Accessibility service enabled successfully: " + serviceName);
        } else {
            Log.e("SystemCtrlUtil", "Failed to enable accessibility service: " + serviceName);
        }

        return success;
    }

    /**
     * 使用 root 权限禁用指定的无障碍服务
     * @param context 上下文
     * @param serviceName 无障碍服务的完整名称（格式：包名/服务类名）
     * @return 是否成功
     */
    public static boolean disableAccessibilityService(Context context, String serviceName) {
        // 先获取当前已启用的服务列表
        String currentServices = android.provider.Settings.Secure.getString(
            context.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        // 如果服务列表为空或不包含该服务，直接返回成功
        if (currentServices == null || currentServices.isEmpty()) {
            Log.d("SystemCtrlUtil", "No accessibility services enabled, nothing to disable");
            return true;
        }

        if (!currentServices.contains(serviceName)) {
            Log.d("SystemCtrlUtil", "Accessibility service not enabled: " + serviceName);
            return true;
        }

        // 从服务列表中移除该服务
        String newServices = currentServices.replace(serviceName, "")
            .replaceAll("::+", ":")  // 替换连续的冒号为单个冒号
            .replaceAll("^:|:$", "");  // 移除开头或结尾的冒号

        // 如果移除后列表为空，禁用无障碍功能
        boolean success;
        if (newServices.isEmpty()) {
            success = RootCommand("settings put secure accessibility_enabled 0");
            if (success) {
                success = RootCommand("settings put secure enabled_accessibility_services \"\"");
            }
        } else {
            // 使用 root 权限更新服务列表
            success = RootCommand("settings put secure enabled_accessibility_services " + newServices);
        }

        if (success) {
            Log.d("SystemCtrlUtil", "Accessibility service disabled successfully: " + serviceName);
        } else {
            Log.e("SystemCtrlUtil", "Failed to disable accessibility service: " + serviceName);
        }

        return success;
    }
}
