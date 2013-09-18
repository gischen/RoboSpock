package pl.polidea.robospock;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.*;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.robolectric.AndroidManifest;
import org.robolectric.MavenCentral;
import org.robolectric.SdkConfig;
import org.robolectric.SdkEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.bytecode.AsmInstrumentingClassLoader;
import org.robolectric.bytecode.Setup;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.util.AnnotationUtil;
import org.spockframework.runtime.Sputnik;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

public class RoboSputnik extends Runner implements Filterable, Sortable {

    private static final MavenCentral MAVEN_CENTRAL = new MavenCentral();

    // we're using interface, because using Sputnik by class would throw
    // "cannot cast from Sputnik to Sputnik"
    private Runner sputnikRunner;

    static ClassLoader classLoader = null;

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

        FsFile fsFile = Fs.currentDirectory();
        String manifestStr = config.manifest().equals(Config.DEFAULT) ? "AndroidManifest.xml" : config.manifest();
        FsFile manifestFile = fsFile.join(manifestStr);
        AndroidManifest appManifest = createAppManifest(manifestFile);
        return appManifest;
    }

    protected AndroidManifest createAppManifest(FsFile manifestFile) {
        if (!manifestFile.exists()) {
            System.out.print("WARNING: No manifest file found at " + manifestFile.getPath() + ".");
            System.out.println("Falling back to the Android OS resources only.");
            System.out.println("To remove this warning, annotate your test class with @Config(manifest=Config.NONE).");
            return null;
        }

        FsFile appBaseDir = manifestFile.getParent();
        return new AndroidManifest(manifestFile, appBaseDir.join("res"), appBaseDir.join("assets"));
    }

    protected SdkConfig pickSdkVersion(AndroidManifest appManifest, Config config) {
        if (config != null && config.emulateSdk() != -1) {
            throw new UnsupportedOperationException("Sorry, emulateSdk is not yet supported... coming soon!");
        }

        if (appManifest != null) {
            // todo: something smarter
            int useSdkVersion = appManifest.getTargetSdkVersion();
        }

        // right now we only have real jars for Ice Cream Sandwich aka 4.1 aka API 16
        return new SdkConfig("4.1.2_r1_rc");
    }

    private SdkEnvironment getEnvironment(final AndroidManifest appManifest, final Config config) {
        final SdkConfig sdkConfig = pickSdkVersion(appManifest, config);

        return createSdkEnvironment(sdkConfig);
    }

    public SdkEnvironment createSdkEnvironment(SdkConfig sdkConfig) {
        Setup setup = new Setup();
        ClassLoader robolectricClassLoader = createRobolectricClassLoader(setup, sdkConfig);
        return new SdkEnvironment(sdkConfig, robolectricClassLoader);
    }

    protected ClassLoader createRobolectricClassLoader(Setup setup, SdkConfig sdkConfig) {
        URL[] urls = MAVEN_CENTRAL.getLocalArtifactUrls(
                null,
                sdkConfig.getSdkClasspathDependencies()).values().toArray(new URL[0]
        );

        return new AsmInstrumentingClassLoader(setup, urls);
    }

    public RoboSputnik(final Class<?> clazz) throws InitializationError {


//        SdkEnvironment sdkEnvironment = getEnvironment(appManifest, config);
//        ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();

        Config config = getConfig(clazz);

        AndroidManifest appManifest = getAppManifest(config);

        Setup setup = new Setup();

        SdkConfig sdkConfig = pickSdkVersion(appManifest, config);

        SdkEnvironment sdkEnvironment = getEnvironment(appManifest, config);

        ClassLoader classLoader = sdkEnvironment.getRobolectricClassLoader();

        try {
            this.sputnikRunner = new Sputnik(classLoader.loadClass(clazz.getName()));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Description getDescription() {
        return sputnikRunner.getDescription();
    }

    @Override
    public void run(final RunNotifier notifier) {
        sputnikRunner.run(notifier);
    }

    @Override
    public void filter(final Filter filter) throws NoTestsRemainException {
        ((Filterable) sputnikRunner).filter(filter);
    }

    @Override
    public void sort(final Sorter sorter) {
        ((Sortable) sputnikRunner).sort(sorter);
    }
}
