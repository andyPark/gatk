package org.broadinstitute.hellbender.utils.help;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.argparser.NamedArgumentDefinition;
import org.broadinstitute.barclay.help.DefaultDocWorkUnitHandler;
import org.broadinstitute.barclay.help.DocWorkUnit;
import org.broadinstitute.barclay.help.HelpDoclet;
import org.broadinstitute.hellbender.engine.FeatureInput;
import org.broadinstitute.hellbender.engine.GATKInputPath;
import org.broadinstitute.hellbender.engine.GATKOutputPath;
import org.broadinstitute.hellbender.engine.GATKPathSpecifier;
import org.broadinstitute.hellbender.exceptions.GATKException;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * The GATK WDL work unit handler that is the WDL gen equivalent of GATKWDLDoclet. Its main task is
 * to convert the types of all arguments for a given work unit (tool) from Java types to WDL-compatible types
 * by updating the freemarker map with the transformed types.
 *
 * NOTE: Methods in this class are intended to be called by Gradle/Javadoc only, and should not be called
 * by methods that are used by the GATK runtime, as this class assumes a dependency on com.sun.javadoc classes
 * which may not be present.
 */
public class GATKWDLWorkUnitHandler extends DefaultDocWorkUnitHandler {

    private final static String GATK_FREEMARKER_TEMPLATE_NAME = "toolWDLTemplate.wdl";

    // List of Java argument types that the WDL generator knows how to convert to a WDL type, along with the
    // corresponding string transform that needs to happen. Some of these are no-ops from a purely string
    // perspective in that there is no actual conversion required because the type names are the same in
    // Java and WDL (i.e, File->File or String->String), but they're included her for completeness and to
    // document the allowed type transitions.
    private final static Map<Class<?>, ImmutablePair<String, String>> javaToWDLTypeMap =
            new HashMap<Class<?>, ImmutablePair<String, String>>() {
                private static final long serialVersionUID = 1L;
                {
                    put(String.class, new ImmutablePair<>("String", "String"));

                    // primitive (or boxed primitive) types
                    put(boolean.class, new ImmutablePair<>("boolean", "Boolean"));
                    put(Boolean.class, new ImmutablePair<>("Boolean", "Boolean"));

                    put(byte.class, new ImmutablePair<>("byte", "Int"));
                    put(Byte.class, new ImmutablePair<>("Byte", "Int"));

                    put(int.class, new ImmutablePair<>("int", "Int"));
                    put(Integer.class, new ImmutablePair<>("Integer", "Int"));

                    //TODO: WDL has no long type, map to Int
                    put(long.class, new ImmutablePair<>("long", "Int"));
                    put(Long.class, new ImmutablePair<>("Long", "Int"));

                    put(float.class, new ImmutablePair<>("float", "Float"));
                    put(Float.class, new ImmutablePair<>("Float", "Float"));
                    put(double.class, new ImmutablePair<>("double", "Float"));
                    put(Double.class, new ImmutablePair<>("Double", "Float"));

                    // File/Path Types
                    put(File.class, new ImmutablePair<>("File", "File"));
                    put(GATKInputPath.class, new ImmutablePair<>("GATKInputPath", "File"));
                    put(GATKOutputPath.class, new ImmutablePair<>("GATKOutputPath", "File"));
                    put(GATKPathSpecifier.class, new ImmutablePair<>("GATKPathSpecifier", "File"));
                    // Note that FeatureInputs require special handling to account for the generic type param
                    put(FeatureInput.class, new ImmutablePair<>("FeatureInput", "File"));
            }
        };

    public GATKWDLWorkUnitHandler(final HelpDoclet doclet) {
        super(doclet);
    }

    /**
     * @param workUnit the classdoc object being processed
     * @return the name of a the freemarker template to be used for the class being documented.
     * Must reside in the folder passed to the Barclay Doclet via the "-settings-dir" parameter to
     * Javadoc.
     */
    @Override
    public String getTemplateName(final DocWorkUnit workUnit) { return GATK_FREEMARKER_TEMPLATE_NAME; }

    @Override
    protected String processNamedArgument(
            final Map<String, Object> argBindings,
            final NamedArgumentDefinition argDef,
            final String fieldCommentText) {
        final String argCategory = super.processNamedArgument(argBindings, argDef, fieldCommentText);

        // replace the java type of the argument with the appropriate wdl type
        final String wdlType = getWDLTypeForArgument(argDef, (String) argBindings.get("type"));
        argBindings.put("type", wdlType);

        // and replace the actual argument name with a wdl-friendly name ("input" and "output" are reserved words
        // in WDL and can't be used for arg names, so use the arg's shortName/synonym if there is one)
        final String actualArgName = (String) argBindings.get("name");
        String wdlName = actualArgName;
        //TODO: Remove the "--" from Barclay!
        if (actualArgName.equalsIgnoreCase("--output") || actualArgName.equalsIgnoreCase("--input")) {
            if (argBindings.get("synonyms") != null) {
                wdlName = "-" + argBindings.get("synonyms").toString();
            } else {
                //TODO: include the tool context in the message
                throw new RuntimeException(String.format(
                        "Can't generate WDL for argument named %s (which is a WDL reserved word)",
                        actualArgName));
            }
        }

        // WDL doesn't accept "-", so change to non-kebab w/underscore
        wdlName = wdlName.substring(2).replace("-", "_");
        argBindings.put("name", "--" + wdlName);

        return argCategory;
    }

    /**
     * Return a String that represents the WDL type for this arg, which is a variant of the  user-friendly doc
     * type chosen by the doc system. Interrogates the structured NamedArgumentDefinition type to transform and
     * determine the resulting WDL type.
     *
     * @param argDef the Barclay NamedArgumentDefinition for this arg
     * @param argDocType the display type as chosen by the Barclay doc system for this arg. this is what
     * @return
     */
    private String getWDLTypeForArgument(
            final NamedArgumentDefinition argDef,
            final String argDocType
    ) {
        final Field argField = argDef.getUnderlyingField();
        final Class<?> argumentClass = argField.getType();

        // start the data type chose by the doc system and transform that based on the underlying
        // java class/type
        String wdlType = argDocType;

        // see if the underlying field is a collection type; if so it needs to map to "Array", and then the
        // type param has to be converted to a WDL type
        // now map Java collections to WDL collection (`Array`)
        if (argDef.isCollection()) {
            if (argumentClass.equals(List.class)) {
                wdlType = wdlType.replace("List", "Array");
            } else if (argumentClass.equals(ArrayList.class)) {
                //TODO: there are a few arguments that are typed as "ArrayList", find and fix these in the source...
                wdlType = wdlType.replace("ArrayList", "Array");
            } else if (argumentClass.equals(Set.class)) {
                wdlType = wdlType.replace("Set", "Array");
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Unrecognized collection type %s for argument %s in work unit %s." +
                                "Argument collection type must be one of List or Set.",
                                argumentClass,
                                argField.getName(),
                                argField.getDeclaringClass()));
            }

            // Convert any Collection type params; this is limited to a single generic type parameter (i.e List<T>),
            // where type T can in turn be a generic type, again with a single type param. This is sufficient to
            // accommodate all existing cases, with the most complex being List<FeatureInput<T>>. If the need arises,
            // supporting more deeply nested generic types will require additional code.
            final Type typeParamType = argField.getGenericType();
            if (typeParamType instanceof ParameterizedType) {
                final ParameterizedType pType = (ParameterizedType) typeParamType;
                final Type genericTypes[] = pType.getActualTypeArguments();
                if (genericTypes.length != 1) {
                    throw new GATKException(String.format(
                            "Generating WDL for tools with args with multiple type parameters is not supported " +
                                    "(class %s for arg %s in %s has multiple type paramaters).",
                            argumentClass,
                            argField.getName(),
                            argField.getDeclaringClass()));
                }
                ParameterizedType pType2 = null;
                Type genericTypes2[];
                Class<?> nestedTypeClass = null;
                try {
                    // we could have nested generic types, like "List<FeatureInput<VariantContext>>", which needs
                    // to translate to "List<File>"
                    if (genericTypes[0] instanceof ParameterizedType) {
                        pType2 = (ParameterizedType) genericTypes[0];
                        genericTypes2 = pType2.getActualTypeArguments();
                        if (genericTypes2.length != 1) {
                            throw new GATKException(String.format(
                                    "Generating WDL for tools with args with multiple type parameters is not supported " +
                                            "(class %s for arg %s in %s has multiple type paramaters).",
                                    argumentClass,
                                    argField.getName(),
                                    argField.getDeclaringClass()));
                        }

                        nestedTypeClass = Class.forName(pType2.getRawType().getTypeName());
                        wdlType = replaceJavaTypeWithWDLType(nestedTypeClass, wdlType, argField.getDeclaringClass().toString());
                    } else {
                        nestedTypeClass = Class.forName(genericTypes[0].getTypeName());
                        wdlType = replaceJavaTypeWithWDLType(nestedTypeClass, wdlType, argField.getDeclaringClass().toString());
                    }
                    return wdlType;
                } catch (ClassNotFoundException e) {
                    throw new GATKException(String.format(
                            "WDL gen can't find class %s for %s",
                            pType2.getRawType().toString(),
                            argField.getDeclaringClass()), e);
                }
            } else {
                throw new GATKException(String.format(
                        "Generic type must have a ParameterizedType (class %s for argument %s/%s)",
                        argumentClass,
                        argField.getName(),
                        argField.getDeclaringClass()));
            }
        }

        wdlType = replaceJavaTypeWithWDLType(argumentClass, wdlType, argField.getDeclaringClass().toString());

        return wdlType;
    }

    //private final convertParamaterizedType()

    private String replaceJavaTypeWithWDLType(final Class<?> argumentClass, final String originalWDLType, final String contextMessage) {
        String wdlType = originalWDLType;
        final Pair<String, String> typeConversionPair = javaToWDLTypeMap.get(argumentClass);
        if (typeConversionPair != null) {
            if (FeatureInput.class.isAssignableFrom(argumentClass)) {
                //TODO: need to handle tags
                if (!wdlType.contains("FeatureInput")) {
                    throw new GATKException(
                            String.format(
                                    "Don't know how to generate a WDL type for %s in work unit %s",
                                    argumentClass,
                                    contextMessage));
                }
                wdlType = originalWDLType.replaceFirst("FeatureInput\\[[a-zA-Z0-9?]+\\]", typeConversionPair.getValue());
            } else {
                wdlType = originalWDLType.replace(typeConversionPair.getKey(), typeConversionPair.getValue());
            }
        } else if (argumentClass.isEnum()) {
            //TODO: should we emit structs for all these ??? into a shared/common WDL file ?
            //System.out.println(String.format("Dangling enum type %s/%s", argumentClass.getName(), wdlType));
            wdlType = originalWDLType.replace(argumentClass.getSimpleName(), "String");
        } else {
            throw new GATKException(
                    String.format(
                            "Can't generate a WDL type for %s, in work unit %s to a WDL type",
                            argumentClass,
                            contextMessage));
        }
        return wdlType;
    }

    /**
     * Add any custom freemarker bindings discovered via custom javadoc tags. Subclasses can override this to
     * provide additional custom bindings.
     *
     * @param currentWorkUnit the work unit for the feature being documented
     */
    @Override
    protected void addCustomBindings(final DocWorkUnit currentWorkUnit) {
        super.addCustomBindings(currentWorkUnit);

        @SuppressWarnings("unchecked")
        final Map<String, List<Map<String, Object>>> argMap =
                (Map<String, List<Map<String, Object>>>) currentWorkUnit.getProperty("arguments");

        // Picard tools use the summary line for the long overview section, so extract that
        // from Picard tools only, and put it in the freemarker map.
        Class<?> toolClass = currentWorkUnit.getClazz();
        if (picard.cmdline.CommandLineProgram.class.isAssignableFrom(toolClass)) {
            final CommandLineProgramProperties clpProperties = currentWorkUnit.getCommandLineProperties();
            currentWorkUnit.setProperty("picardsummary", clpProperties.summary());
        }
    }

}
