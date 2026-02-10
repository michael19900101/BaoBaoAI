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
}
