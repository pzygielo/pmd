/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.renderers;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sourceforge.pmd.properties.PropertyDescriptor;
import net.sourceforge.pmd.util.AssertionUtil;

/**
 * This class handles the creation of Renderers.
 *
 * @see Renderer
 */
public final class RendererFactory {

    private static final Logger LOG = LoggerFactory.getLogger(RendererFactory.class);

    private static final Map<String, Class<? extends Renderer>> REPORT_FORMAT_TO_RENDERER;

    static {
        Map<String, Class<? extends Renderer>> map = new TreeMap<>();
        map.put(CodeClimateRenderer.NAME, CodeClimateRenderer.class);
        map.put(XMLRenderer.NAME, XMLRenderer.class);
        map.put(IDEAJRenderer.NAME, IDEAJRenderer.class);
        map.put(TextColorRenderer.NAME, TextColorRenderer.class);
        map.put(TextRenderer.NAME, TextRenderer.class);
        map.put(TextPadRenderer.NAME, TextPadRenderer.class);
        map.put(EmacsRenderer.NAME, EmacsRenderer.class);
        map.put(CSVRenderer.NAME, CSVRenderer.class);
        map.put(HTMLRenderer.NAME, HTMLRenderer.class);
        map.put(XSLTRenderer.NAME, XSLTRenderer.class);
        map.put(YAHTMLRenderer.NAME, YAHTMLRenderer.class);
        map.put(SummaryHTMLRenderer.NAME, SummaryHTMLRenderer.class);
        map.put(VBHTMLRenderer.NAME, VBHTMLRenderer.class);
        map.put(EmptyRenderer.NAME, EmptyRenderer.class);
        map.put(JsonRenderer.NAME, JsonRenderer.class);
        map.put(SarifRenderer.NAME, SarifRenderer.class);
        REPORT_FORMAT_TO_RENDERER = Collections.unmodifiableMap(map);
    }

    private RendererFactory() { }

    /**
     * Retrieves a collection of all supported renderer names.
     *
     * @return The set of all supported renderer names.
     */
    public static Set<String> supportedRenderers() {
        return Collections.unmodifiableSet(REPORT_FORMAT_TO_RENDERER.keySet());
    }

    /**
     * Construct an instance of a Renderer based on report format name.
     *
     * @param reportFormat
     *            The report format name.
     * @param properties
     *            Initialization properties for the corresponding Renderer.
     * @return A Renderer instance.
     */
    public static Renderer createRenderer(String reportFormat, Properties properties) {
        AssertionUtil.requireParamNotNull("reportFormat", reportFormat);
        Class<? extends Renderer> rendererClass = getRendererClass(reportFormat);
        Constructor<? extends Renderer> constructor = getRendererConstructor(rendererClass);

        Renderer renderer;
        try {
            if (constructor.getParameterTypes().length > 0) {
                LOG.warn(
                        "The renderer uses a deprecated mechanism to use the properties. Please define the needed properties with this.definePropertyDescriptor(..).");
                renderer = constructor.newInstance(properties);
            } else {
                renderer = constructor.newInstance();

                for (PropertyDescriptor<?> prop : renderer.getPropertyDescriptors()) {
                    String value = properties.getProperty(prop.name());
                    if (value != null) {
                        @SuppressWarnings("unchecked")
                        PropertyDescriptor<Object> prop2 = (PropertyDescriptor<Object>) prop;
                        Object valueFrom = prop2.serializer().fromString(value);
                        renderer.setProperty(prop2, valueFrom);
                    }
                }
            }
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Unable to construct report renderer class: " + e.getLocalizedMessage(), e);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException(
                    "Unable to construct report renderer class: " + e.getTargetException().getLocalizedMessage(), e);
        }
        // Warn about legacy report format usages
        if (REPORT_FORMAT_TO_RENDERER.containsKey(reportFormat) && !reportFormat.equals(renderer.getName())) {
            LOG.warn("Report format '{}' is deprecated, and has been replaced with '{}'. "
                    + "Future versions of PMD will remove support for this deprecated Report format usage.",
                    reportFormat, renderer.getName());
        }
        return renderer;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Renderer> getRendererClass(String reportFormat) {
        AssertionUtil.requireParamNotNull("reportFormat", reportFormat);
        Class<? extends Renderer> rendererClass = REPORT_FORMAT_TO_RENDERER.get(reportFormat);

        // Look up a custom renderer class
        if (rendererClass == null && !"".equals(reportFormat)) {
            try {
                Class<?> clazz = Class.forName(reportFormat);
                if (!Renderer.class.isAssignableFrom(clazz)) {
                    throw new IllegalArgumentException("Custom report renderer class does not implement the "
                            + Renderer.class.getName() + " interface.");
                } else {
                    rendererClass = (Class<? extends Renderer>) clazz;
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Can't find the custom format " + reportFormat + ": " + e);
            }
        }
        return rendererClass;
    }

    private static Constructor<? extends Renderer> getRendererConstructor(Class<? extends Renderer> rendererClass) {
        Constructor<? extends Renderer> constructor = null;

        // 1) Properties constructor? - deprecated
        try {
            constructor = rendererClass.getConstructor(Properties.class);
            if (!Modifier.isPublic(constructor.getModifiers())) {
                constructor = null;
            }
        } catch (NoSuchMethodException ignored) {
            // Ignored, we'll check default constructor next
        }

        // 2) No-arg constructor?
        try {
            constructor = rendererClass.getConstructor();
            if (!Modifier.isPublic(constructor.getModifiers())) {
                constructor = null;
            }
        } catch (NoSuchMethodException ignored) {
            // Ignored, we'll eventually throw an exception, if there is no constructor
        }

        if (constructor == null) {
            throw new IllegalArgumentException(
                    "Unable to find either a public java.util.Properties or no-arg constructors for Renderer class: "
                            + rendererClass.getName());
        }
        return constructor;
    }
}
