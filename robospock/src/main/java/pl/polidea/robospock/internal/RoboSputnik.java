package pl.polidea.robospock.internal;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.*;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.bytecode.AsmInstrumentingClassLoader;
import org.robolectric.bytecode.Setup;
import org.robolectric.bytecode.ShadowMap;
import org.robolectric.res.DocumentLoader;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.res.ResourceLoader;
import org.robolectric.util.AnnotationUtil;
import org.spockframework.runtime.Sputnik;
import org.spockframework.runtime.model.SpecInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.SecureRandom;
import java.util.*;

public class RoboSputnik extends Runner implements Filterable, Sortable {

    private DependencyResolver dependencyResolver;

    private static final Map<Class<? extends RoboSputnik>, EnvHolder> envHoldersByTestRunner =
            new HashMap<Class<? extends RoboSputnik>, EnvHolder>();

    private static final Map<AndroidManifest, ResourceLoader> resourceLoadersByAppManifest = new HashMap<AndroidManifest, ResourceLoader>();

    private static Class<? extends RoboSputnik> lastTestRunnerClass;
    private static SdkConfig lastSdkConfig;
    private static SdkEnvironment lastSdkEnvironment;

    private final EnvHolder envHolder;

    private Object sputnik;

    static {
        new SecureRandom(); // this starts up the Poller SunPKCS11-Darwin thread early, outside of any Robolectric classloader
    }

    public RoboSputnik(Class<?> clazz) throws InitializationError {

        // Ripped from RobolectricTestRunner

        EnvHolder envHolder;
        synchronized (envHoldersByTestRunner) {
            Class<? extends RoboSputnik> testRunnerClass = getClass();
            envHolder = envHoldersByTestRunner.get(testRunnerClass);
            if (envHolder == null) {
                envHolder = new EnvHolder();
                envHoldersByTestRunner.put(testRunnerClass, envHolder);
            }
        }
        this.envHolder = envHolder;

        final Config config = getConfig(clazz);
        AndroidManifest appManifest = getAppManifest(config);
        SdkEnvironment sdkEnvironment = getEnvironment(appManifest, config);

        // todo: is this really needed?
        Thread.currentThread().setContextClassLoader(sdkEnvironment.getRobolectricClassLoader());

        Class bootstrappedTestClass = sdkEnvironment.bootstrappedClass(clazz);

        // Since we have bootstrappedClass we may properly initialize

        try {

            this.sputnik = sdkEnvironment
                    .bootstrappedClass(Sputnik.class)
                    .getConstructor(Class.class)
                    .newInstance(bootstrappedTestClass);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // let's manually add our initializers

        for(Method method : sputnik.getClass().getDeclaredMethods()) {
            if(method.getName() == "getSpec") {
                method.setAccessible(true);
                try {
                    Object spec = method.invoke(sputnik);

                    // Interceptor registers on construction
                    sdkEnvironment
                            .bootstrappedClass(RoboSpockInterceptor.class)
                            .getConstructor(
                                    sdkEnvironment.bootstrappedClass(SpecInfo.class),
                                    SdkEnvironment.class,
                                    Config.class,
                                    AndroidManifest.class
                            ).newInstance(spec, sdkEnvironment, config, appManifest);

                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                } catch (NoSuchMethodException e) {
                    throw new RuntimeException(e);
                } catch (InstantiationException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public Config getConfig(Class<?> clazz) {
        Config config = AnnotationUtil.defaultsFor(Config.class);

        Config globalConfig = Config.Implementation.fromProperties(getConfigProperties());
        if (globalConfig != null) {
            config = new Config.Implementation(config, globalConfig);
        }

        Config classConfig = clazz.getAnnotation(Config.class);
        if (classConfig != null) {
            config = new Config.Implementation(config, classConfig);
        }

        return config;
    }

    protected Properties getConfigProperties() {
        ClassLoader classLoader = getClass().getClassLoader();
        InputStream resourceAsStream = classLoader.getResourceAsStream("org.robolectric.Config.properties");
        if (resourceAsStream == null) return null;
        Properties properties = new Properties();
        try {
            properties.load(resourceAsStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return properties;
    }

    protected AndroidManifest getAppManifest(Config config) {
        if (config.manifest().equals(Config.NONE)) {
            return null;
        }

        String manifestProperty = System.getProperty("android.manifest");
        String resourcesProperty = System.getProperty("android.resources");
        String assetsProperty = System.getProperty("android.assets");

        FsFile baseDir;
        FsFile manifestFile;
        FsFile resDir;
        FsFile assetsDir;

        boolean defaultManifest = config.manifest().equals(Config.DEFAULT);
        if (defaultManifest && manifestProperty != null) {
            manifestFile = Fs.fileFromPath(manifestProperty);
            baseDir = manifestFile.getParent();
            resDir = Fs.fileFromPath(resourcesProperty);
            assetsDir = Fs.fileFromPath(assetsProperty);
        } else {
            manifestFile = getBaseDir().join(defaultManifest ? AndroidManifest.DEFAULT_MANIFEST_NAME : config.manifest());
            baseDir = manifestFile.getParent();
            resDir = baseDir.join(config.resourceDir());
            assetsDir = baseDir.join(AndroidManifest.DEFAULT_ASSETS_FOLDER);
        }

        List<FsFile> libraryDirs = null;
        if (config.libraries().length > 0) {
            libraryDirs = new ArrayList<FsFile>();
            for (String libraryDirName : config.libraries()) {
                libraryDirs.add(baseDir.join(libraryDirName));
            }
        }

        synchronized (envHolder) {
            AndroidManifest appManifest;
            appManifest = envHolder.appManifestsByFile.get(manifestFile);
            if (appManifest == null) {
                long startTime = System.currentTimeMillis();
                appManifest = createAppManifest(manifestFile, resDir, assetsDir);

                if (libraryDirs != null) {
                    appManifest.setLibraryDirectories(libraryDirs);
                }

                if (DocumentLoader.DEBUG_PERF)
                    System.out.println(String.format("%4dms spent in %s", System.currentTimeMillis() - startTime, manifestFile));

                envHolder.appManifestsByFile.put(manifestFile, appManifest);
            }
            return appManifest;
        }
    }

    protected FsFile getBaseDir() {
        return Fs.currentDirectory();
    }

    protected AndroidManifest createAppManifest(FsFile manifestFile, FsFile resDir, FsFile assetsDir) {
        if (!manifestFile.exists()) {
            System.out.print("WARNING: No manifest file found at " + manifestFile.getPath() + ".");
            System.out.println("Falling back to the Android OS resources only.");
            System.out.println("To remove this warning, annotate your test class with @Config(manifest=Config.NONE).");
            return null;
        }
        AndroidManifest manifest = new AndroidManifest(manifestFile, resDir, assetsDir);
        String packageName = System.getProperty("android.package");
        manifest.setPackageName(packageName);
        return manifest;
    }


    private SdkEnvironment getEnvironment(final AndroidManifest appManifest, final Config config) {
        final SdkConfig sdkConfig = pickSdkVersion(appManifest, config);

        // keep the most recently-used SdkEnvironment strongly reachable to prevent thrashing in low-memory situations.
        if (getClass().equals(lastTestRunnerClass) && sdkConfig.equals(lastSdkConfig)) {
            return lastSdkEnvironment;
        }

        lastTestRunnerClass = null;
        lastSdkConfig = null;
        lastSdkEnvironment = envHolder.getSdkEnvironment(sdkConfig, new SdkEnvironment.Factory() {
            @Override public SdkEnvironment create() {
                return createSdkEnvironment(sdkConfig);
            }
        });
        lastTestRunnerClass = getClass();
        lastSdkConfig = sdkConfig;
        return lastSdkEnvironment;
    }

    public SdkEnvironment createSdkEnvironment(SdkConfig sdkConfig) {
        Setup setup = createSetup();
        ClassLoader robolectricClassLoader = createRobolectricClassLoader(setup, sdkConfig);
        return new SdkEnvironment(sdkConfig, robolectricClassLoader);
    }

    protected ClassLoader createRobolectricClassLoader(Setup setup, SdkConfig sdkConfig) {
        URL[] urls = getJarResolver().getLocalArtifactUrls(sdkConfig.getSdkClasspathDependencies());
        return new AsmInstrumentingClassLoader(setup, urls);
    }


    protected DependencyResolver getJarResolver() {
        if (dependencyResolver == null) {
            if (Boolean.getBoolean("robolectric.offline")) {
                String dependencyDir = System.getProperty("robolectric.dependency.dir", ".");
                dependencyResolver = new LocalDependencyResolver(new File(dependencyDir));
            } else {
                File cacheDir = new File(new File(System.getProperty("java.io.tmpdir")), "robolectric");
                cacheDir.mkdir();

                if (cacheDir.exists()) {
                    dependencyResolver = new RoboSpockCachedDepedencyResolver(new MavenDependencyResolver(), cacheDir, 60 * 60 * 24 * 1000);
                } else {
                    dependencyResolver = new MavenDependencyResolver();
                }
            }
        }

        return dependencyResolver;
    }

    public Setup createSetup() {
        return new Setup();
    }

    protected SdkConfig pickSdkVersion(AndroidManifest appManifest, Config config) {
        if (config != null && config.emulateSdk() > 0) {
            return new SdkConfig(config.emulateSdk());
        } else {
            if (appManifest != null) {
                return new SdkConfig(appManifest.getTargetSdkVersion());
            } else {
                return SdkConfig.getDefaultSdk();
            }
        }
    }

    public Description getDescription() {
        return ((Runner) sputnik).getDescription();
    }

    public void run(RunNotifier notifier) {
        ((Runner) sputnik).run(notifier);
    }

    public void filter(Filter filter) throws NoTestsRemainException {
        ((Filterable) sputnik).filter(filter);
    }

    public void sort(Sorter sorter) {
        ((Sortable) sputnik).sort(sorter);
    }


}
