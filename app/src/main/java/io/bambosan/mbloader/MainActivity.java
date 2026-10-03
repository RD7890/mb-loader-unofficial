package io.bambosan.mbloader;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    public static final String MC_PACKAGE_NAME = "com.mojang.minecraftpe";

    /** Directory that actually holds Minecraft's .so files (its nativeLibraryDir or our code cache copy). */
    private String mcLibDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Support for shared element transition from splash screen
        postponeEnterTransition();
        
        // Add entry animation
        View rootView = findViewById(android.R.id.content);
        rootView.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in));
        
        // Complete the postponed shared element transition
        startPostponedEnterTransition();
        
        if (savedInstanceState == null) {
            MblLog.ensureStorageAccess(this);
            // --- Apply custom window flags ---
            getWindow().setStatusBarColor(getColor(R.color.background));
            getWindow().setNavigationBarColor(getColor(R.color.background));
            
            // --- Set content view and attach navigation ---
            setContentView(R.layout.activity_main);
            setupNavigation();
            
            // --- Set default fragment ---
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new HomeFragment())
                .commit();
        }
    }

    public void startLauncher(Handler handler, TextView listener, ScrollView logScrollView, String launcherDexName, String mcPackageName) {    
        Executors.newSingleThreadExecutor().execute(() -> {
            MblLog.startSession(getApplicationContext(), launcherDexName, mcPackageName);
            try {
                File cacheDexDir = new File(getCodeCacheDir(), "dex");
                handleCacheCleaning(cacheDexDir, handler, listener, logScrollView);
                ApplicationInfo mcInfo = null;
                try {
                    mcInfo = getPackageManager().getApplicationInfo(mcPackageName, PackageManager.GET_META_DATA);
                } catch(Exception e) {
                    handler.post(() -> alertAndExit("Minecraft not found", "Perhaps you don't have it installed?"));
                    return;
                }
                Object pathList = getPathList(getClassLoader());
                processDexFiles(mcInfo, cacheDexDir, pathList, handler, listener, logScrollView, launcherDexName);
                if (!processNativeLibraries(mcInfo, pathList, handler, listener, logScrollView)) {
                    return;
                }
                preloadMcDeps(handler, listener, logScrollView);
                launchMinecraft(mcInfo);
            } catch (Exception e) {
                MblLog.logThrowable(getApplicationContext(), "startLauncher failed", e);
                String logMessage = e.getCause() != null ? e.getCause().toString() : e.toString();
                handler.post(() -> {
                    MblLog.uiSet(listener, "Launching failed: " + logMessage);
                    logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                });
            }
        });    
    }
    @SuppressLint("SetTextI18n")
    private void handleCacheCleaning(@NotNull File cacheDexDir, Handler handler, TextView listener, ScrollView logScrollView) {
        if (cacheDexDir.exists() && cacheDexDir.isDirectory()) {
            handler.post(() -> {
                MblLog.uiSet(listener, "-> " + cacheDexDir.getAbsolutePath() + " not empty, do cleaning");
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            });
            for (File file : Objects.requireNonNull(cacheDexDir.listFiles())) {
                if (file.delete()) {
                    handler.post(() -> {
                        MblLog.ui(listener, "\n-> " + file.getName() + " deleted");
                        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                    });
                }
            }
        } else {
            handler.post(() -> {
                MblLog.uiSet(listener, "-> " + cacheDexDir.getAbsolutePath() + " is empty, skip cleaning");
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            });
        }
    }

    private Object getPathList(@NotNull ClassLoader classLoader) throws Exception {
        Field pathListField = Objects.requireNonNull(classLoader.getClass().getSuperclass()).getDeclaredField("pathList");
        pathListField.setAccessible(true);
        return pathListField.get(classLoader);
    }

    private void processDexFiles(ApplicationInfo mcInfo, File cacheDexDir, @NotNull Object pathList, @NotNull Handler handler, TextView listener, ScrollView logScrollView, String launcherDexName) throws Exception {
        Method addDexPath = pathList.getClass().getDeclaredMethod("addDexPath", String.class, File.class);
        File launcherDex = new File(cacheDexDir, launcherDexName);

        copyFile(getAssets().open(launcherDexName), launcherDex);
        handler.post(() -> {
             MblLog.ui(listener, "\n-> " + launcherDexName + " copied to " + launcherDex.getAbsolutePath());
             logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });

        if (launcherDex.setReadOnly()) {
            addDexPath.invoke(pathList, launcherDex.getAbsolutePath(), null);
            handler.post(() -> {
                MblLog.ui(listener, "\n-> " + launcherDexName + " added to dex path list");
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            });
        }

        try (ZipFile zipFile = new ZipFile(mcInfo.sourceDir)) {
            for (int i = 10; i >= 0; i--) {
                String dexName = "classes" + (i == 0 ? "" : i) + ".dex";
                ZipEntry dexFile = zipFile.getEntry(dexName);
                if (dexFile != null) {
                    File mcDex = new File(cacheDexDir, dexName);
                    copyFile(zipFile.getInputStream(dexFile), mcDex);
                     handler.post(() -> {
                         MblLog.ui(listener, "\n-> " + mcInfo.sourceDir + "/" + dexName + " copied to " + mcDex.getAbsolutePath());
                         logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                     });
                    if (mcDex.setReadOnly()) {
                        addDexPath.invoke(pathList, mcDex.getAbsolutePath(), null);
                        handler.post(() -> {
                             MblLog.ui(listener, "\n-> " + dexName + " added to dex path list");
                             logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                        });
                    }
                }
            }
        } catch (Throwable th) {}
         handler.post(() -> {
             MblLog.ui(listener, "\n-> Processed dex files.");
             logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
         });
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private boolean processNativeLibraries(ApplicationInfo mcInfo, @NotNull Object pathList, @NotNull Handler handler, TextView listener, ScrollView logScrollView) throws Exception {
        FileInputStream inStream = new FileInputStream(getApkWithLibs(mcInfo));
 		BufferedInputStream bufInStream = new BufferedInputStream(inStream);
 		ZipInputStream inZipStream = new ZipInputStream(bufInStream);
 		if (!checkLibCompatibility(inZipStream)) {
 		    handler.post(() -> alertAndExit("Wrong Minecraft architecture", "The Minecraft you have installed does not support the same main architecture (" + Build.SUPPORTED_ABIS[0] + ") your device uses, MBLoader can't work with it"));
 		    return false;
 		} 		    
        Method addNativePath = pathList.getClass().getDeclaredMethod("addNativePath", Collection.class);
        ArrayList<String> libDirList = new ArrayList<>();
        File libdir = new File(mcInfo.nativeLibraryDir);
		if (libdir.list() == null || libdir.list().length == 0 
		 || (mcInfo.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) {
			loadUnextractedLibs(mcInfo);
			libDirList.add(getCodeCacheDir().getAbsolutePath() + "/");
			mcLibDir = getCodeCacheDir().getAbsolutePath();
		} else {
            libDirList.add(mcInfo.nativeLibraryDir);
            mcLibDir = mcInfo.nativeLibraryDir;
        }
        addNativePath.invoke(pathList, libDirList);
        handler.post(() -> {
            MblLog.ui(listener, "\n-> " + mcInfo.nativeLibraryDir + " added to native library directory path");
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
        return true;
    }
    
    private static Boolean checkLibCompatibility(ZipInputStream zip) throws Exception{
         ZipEntry ze = null;
         String requiredLibDir = "lib/" + Build.SUPPORTED_ABIS[0] + "/";
         while ((ze = zip.getNextEntry()) != null) {
             if (ze.getName().startsWith(requiredLibDir)) {
                 return true;
             }
         }
         zip.close();
         return false;
     }
     
    private void alertAndExit(String issue, String description) {
        AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this).create();
        alertDialog.setTitle(issue);
        alertDialog.setMessage(description);
        alertDialog.setCancelable(false);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Exit",
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
        alertDialog.show();
    }
     
    private void loadUnextractedLibs(ApplicationInfo appInfo) throws Exception {
		FileInputStream inStream = new FileInputStream(getApkWithLibs(appInfo));
		BufferedInputStream bufInStream = new BufferedInputStream(inStream);
		ZipInputStream inZipStream = new ZipInputStream(bufInStream);
		String zipPath = "lib/" + Build.SUPPORTED_ABIS[0] + "/";
		String outPath = getCodeCacheDir().getAbsolutePath() + "/";
		File dir = new File(outPath);
		dir.mkdir();
		extractDir(appInfo, inZipStream, zipPath, outPath);
	}
	
	public String getApkWithLibs(ApplicationInfo pkg) throws PackageManager.NameNotFoundException {
		// get installed split's Names
		String[] sn=pkg.splitSourceDirs;

		// check whether if it's really split or not
		if (sn != null && sn.length > 0) {
			String cur_abi = Build.SUPPORTED_ABIS[0].replace('-','_');
			// search installed splits
			for(String n:sn){
				//check whether is the one required
				if(n.contains(cur_abi)){
				// yes, it's installed!
					return n;
				}
			}
		}
		// couldn't find!
		return pkg.sourceDir;
	}
	
	private static void extractDir(ApplicationInfo mcInfo, ZipInputStream zip, String zip_folder, String out_folder) throws Exception {
        ZipEntry ze = null;
        while ((ze = zip.getNextEntry()) != null) {
            if (ze.getName().startsWith(zip_folder) && !ze.getName().contains("c++_shared")) {
				String strippedName = ze.getName().substring(zip_folder.length());
				String path = out_folder + "/" + strippedName;
				OutputStream out = new FileOutputStream(path);
				BufferedOutputStream outBuf = new BufferedOutputStream(out);
                byte[] buffer = new byte[9000];
                int len;
                while ((len = zip.read(buffer)) != -1) {
                    outBuf.write(buffer, 0, len);
                }
                outBuf.close();
            }
        }
        zip.close();
    }

    /**
     * Newer Minecraft builds (1.26.x, Google Play "PairIP" protected) ship libminecraftpe.so with
     * DT_NEEDED entries on libpairipcore.so, libPlayFabMultiplayer.so, libHttpClient.Android.so, libmaesdk.so,
     * libfmod.so and libc++_shared.so. Android's linker resolves those inside THIS app's classloader
     * namespace, which does not see MC's lib dir, so dlopen fails with
     * 'library "libpairipcore.so" not found: needed by libminecraftpe.so'.
     *
     * Fix: map the dependencies ourselves first, by absolute path, so the linker finds them by soname.
     *
     * libpairipcore.so is special: System.loadLibrary("pairipcore") runs its JNI_OnLoad (PairIP VM init)
     * which segfaults inside this process (see logs). Its other users (PlayFab, maesdk, libminecraftpe)
     * only import ExecuteProgram. So by default every dependency except c++_shared/fmod is mapped with a
     * plain dlopen() (NativePreload), which never calls JNI_OnLoad - the same thing the linker would do
     * in the real Minecraft app. Minecraft's own Java code still calls System.loadLibrary later.
     *
     * Mode can be switched without rebuilding via  mbl-logs/preload_mode.txt :
     *   dlopen     (default) pairipcore + others via dlopen, no JNI_OnLoad
     *   mainthread          pairipcore via System.loadLibrary on the UI thread (runs JNI_OnLoad there)
     *
     * This has to run BEFORE the Launcher activity starts (MC's MainActivity.<clinit> loads
     * libminecraftpe.so before Launcher's own static block runs).
     */
    private void preloadMcDeps(@NotNull Handler handler, TextView listener, ScrollView logScrollView) {
        String mode = MblLog.readPreloadMode(getApplicationContext());
        preloadLog(handler, listener, logScrollView, "\n-> preload mode: " + mode + ", lib dir: " + mcLibDir);

        // 1) c++_shared + fmod: plain System.loadLibrary, known to work (see earlier logs).
        for (String lib : new String[]{"c++_shared", "fmod"}) {
            try {
                System.loadLibrary(lib);
                preloadLog(handler, listener, logScrollView, "\n-> preloaded lib" + lib + ".so");
            } catch (Throwable t) {
                Log.w("MBL", "preload failed: " + lib, t);
                preloadLog(handler, listener, logScrollView, "\n-> skipped lib" + lib + ".so (" + t.getMessage() + ")");
            }
        }

        // 2) libpairipcore.so
        if ("mainthread".equals(mode)) {
            preloadLog(handler, listener, logScrollView, "\n-> loading libpairipcore.so on main thread (JNI_OnLoad will run)");
            String err = loadLibraryOnMainThread("pairipcore");
            preloadLog(handler, listener, logScrollView, err == null
                    ? "\n-> preloaded libpairipcore.so (main thread)"
                    : "\n-> FAILED libpairipcore.so (main thread): " + err);
        } else {
            dlopenDep("pairipcore", handler, listener, logScrollView);
        }

        // 3) remaining deps of libminecraftpe.so, mapped without JNI_OnLoad
        for (String lib : new String[]{"PlayFabMultiplayer", "HttpClient.Android", "maesdk"}) {
            dlopenDep(lib, handler, listener, logScrollView);
        }
    }

    private void dlopenDep(String lib, Handler handler, TextView listener, ScrollView logScrollView) {
        if (!NativePreload.AVAILABLE) {
            preloadLog(handler, listener, logScrollView, "\n-> FAILED lib" + lib + ".so: libmblpreload.so not loadable");
            return;
        }
        File f = new File(mcLibDir, "lib" + lib + ".so");
        if (!f.exists()) {
            preloadLog(handler, listener, logScrollView, "\n-> skipped lib" + lib + ".so (not in " + mcLibDir + ")");
            return;
        }
        String err = NativePreload.dlopenGlobal(f.getAbsolutePath());
        preloadLog(handler, listener, logScrollView, err == null
                ? "\n-> mapped lib" + lib + ".so (dlopen, no JNI_OnLoad)"
                : "\n-> FAILED lib" + lib + ".so: " + err);
    }

    private String loadLibraryOnMainThread(String lib) {
        final String[] error = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                System.loadLibrary(lib);
            } catch (Throwable t) {
                error[0] = String.valueOf(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) return "timeout waiting for main thread";
        } catch (InterruptedException e) {
            return "interrupted";
        }
        return error[0];
    }

    private void preloadLog(Handler handler, TextView listener, ScrollView logScrollView, String line) {
        handler.post(() -> {
            MblLog.ui(listener, line);
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void launchMinecraft(@NotNull ApplicationInfo mcInfo) throws ClassNotFoundException {
        Class<?> launcherClass = getClassLoader().loadClass("com.mojang.minecraftpe.Launcher");
        // We do this to preserve data that apps like file managers pass 
        Intent mcActivity = getIntent().setClass(this, launcherClass);
        mcActivity.putExtra("MC_SRC", mcInfo.sourceDir);

        if (mcInfo.splitSourceDirs != null) {
            ArrayList<String> listSrcSplit = new ArrayList<>();
            Collections.addAll(listSrcSplit, mcInfo.splitSourceDirs);
            mcActivity.putExtra("MC_SPLIT_SRC", listSrcSplit);
        }

        MblLog.write("[MBL] startActivity(com.mojang.minecraftpe.Launcher) - from here on Minecraft's own logs follow");
        startActivity(mcActivity);
        finish();
    }

    private void handleException(@NotNull Exception e, @NotNull Intent fallbackActivity) {
        String logMessage = e.getCause() != null ? e.getCause().toString() : e.toString();
        fallbackActivity.putExtra("LOG_STR", logMessage);
        startActivity(fallbackActivity);
        finish();
    }

    private static void copyFile(InputStream from, @NotNull File to) throws IOException {
        File parentDir = to.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directories");
        }
        if (!to.exists() && !to.createNewFile()) {
            throw new IOException("Failed to create new file");
        }
        try (BufferedInputStream input = new BufferedInputStream(from);
             BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(to.toPath()))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        }
    }
    
    private void setupNavigation() {
        // Setup bottom navigation
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        
        // Set initial fragment
        if (getSupportFragmentManager().findFragmentById(R.id.fragment_container) == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new HomeFragment())
                .commit();
            
            // Select home item initially
            bottomNav.setSelectedItemId(R.id.navigation_home);
        }
        
        // Animate nav items using state list animator
        bottomNav.setItemIconTintList(getColorStateList(R.color.nav_item_color));
        bottomNav.setItemTextColor(getColorStateList(R.color.nav_item_color));
        bottomNav.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(
                this, R.animator.nav_item_animation));
                
        // Track the current selected item to determine animation direction
        final int[] currentItem = {R.id.navigation_home};
                
        // Handle navigation item selection
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int enterAnim = R.anim.slide_in_right;
            int exitAnim = R.anim.slide_out_left;
            
            // Determine animation direction based on item position
            if (getNavigationItemIndex(item.getItemId()) < getNavigationItemIndex(currentItem[0])) {
                enterAnim = R.anim.slide_in_left;
                exitAnim = R.anim.slide_out_right;
            }
            
            // Create appropriate fragment
            if (item.getItemId() == R.id.navigation_home) {
                selectedFragment = new HomeFragment();
            } else if (item.getItemId() == R.id.navigation_settings) {
                selectedFragment = new SettingsFragment();
            }

            if (selectedFragment != null) {
                // Update current item
                currentItem[0] = item.getItemId();
                
                // Perform fragment transition with animations
                getSupportFragmentManager().beginTransaction()
                    .setCustomAnimations(
                        enterAnim,
                        exitAnim
                    )
                    .replace(R.id.fragment_container, selectedFragment)
                    .commit();
                return true;
            }
            return false;
        });
    }
    
    // Helper method to get the index of navigation items
    private int getNavigationItemIndex(int itemId) {
        if (itemId == R.id.navigation_home) return 0;
        if (itemId == R.id.navigation_settings) return 1;
        return 0;
    }
}
