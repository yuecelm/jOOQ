/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jooq.exception.ConfigurationException;
import org.jooq.tools.Convert;
import org.jooq.tools.reflect.Reflect;
import org.jooq.tools.reflect.ReflectException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * This class allows for mashalling / unmarshalling XML content to jOOQ
 * configuration objects.
 * <p>
 * With jOOQ 3.12, the JAXB dependency has been removed in favour of this home
 * grown solution. Due to the modularisation that happened with JDK 9+ and the
 * removal of JAXB from the JDK 11+, it is unreasonable to leave the burden of
 * properly configuring transitive JAXB dependency to jOOQ users.
 *
 * @author Lukas Eder
 */
public class MiniJAXB {

    public static String marshal(Object object) {
        StringWriter writer = new StringWriter();
        marshal(object, writer);
        return writer.toString();
    }

    public static void marshal(Object object, OutputStream out) {
        marshal(object, new OutputStreamWriter(out));
    }

    public static void marshal(Object object, Writer out) {
        try {
            XmlRootElement e = object.getClass().getAnnotation(XmlRootElement.class);
            if (e != null)
                out.write("<" + e.name() + ">");

            out.write(object.toString());

            if (e != null)
                out.write("</" + e.name() + ">");
        }
        catch (Exception e) {
            throw new ConfigurationException("Cannot print object", e);
        }
    }

    public static <T> T unmarshal(InputStream in, Class<T> type) {
        return unmarshal0(new InputSource(in), type);
    }

    public static <T> T unmarshal(String xml, Class<T> type) {
        return unmarshal0(new InputSource(new StringReader(xml)), type);
    }

    public static <T> T unmarshal(File xml, Class<T> type) {
        try {
            return unmarshal0(new InputSource(new FileInputStream(xml)), type);
        }
        catch (Exception e) {
            throw new ConfigurationException("Error while opening file", e);
        }
    }

    private static <T> T unmarshal0(InputSource in, Class<T> type) {
        try {
            Document document = builder().parse(in);
            T result = Reflect.on(type).create().get();
            unmarshal0(result, document.getDocumentElement());
            return result;
        }
        catch (Exception e) {
            throw new ConfigurationException("Error while reading xml", e);
        }
    }

    private static void unmarshal0(Object result, Element element) throws Exception {
        if (result == null)
            return;

        Class<?> type = result.getClass();
        for (Field child : type.getDeclaredFields()) {
            int modifiers = child.getModifiers();
            if (Modifier.isFinal(modifiers) ||
                Modifier.isStatic(modifiers))
                continue;

            XmlElementWrapper w = child.getAnnotation(XmlElementWrapper.class);
            XmlElement e = child.getAnnotation(XmlElement.class);
            XmlJavaTypeAdapter a = child.getAnnotation(XmlJavaTypeAdapter.class);

            String childName = child.getName();
            String childElementName =
                w != null
              ? "##default".equals(w.name())
                  ? child.getName()
                  : w.name()
              : e == null || "##default".equals(e.name())
              ? childName
              : e.name();

            Element childElement = child(element, childElementName);
            if (childElement == null)
                continue;

            Class<?> childType = child.getType();
            if (List.class.isAssignableFrom(childType) && w != null && e != null) {
                List<Object> list = new ArrayList<Object>();
                unmarshalList0(list, childElement, e.name(), (Class<?>) ((ParameterizedType) child.getGenericType()).getActualTypeArguments()[0]);
                Reflect.on(result).set(childName, list);
            }
            else if (childType.getAnnotation(XmlEnum.class) != null) {
                Reflect.on(result).set(childName, Convert.convert(childElement.getTextContent().trim(), childType));
            }
            else if (childType.getAnnotation(XmlType.class) != null) {
                Object object = Reflect.on(childType).create().get();
                Reflect.on(result).set(childName, object);

                unmarshal0(object, childElement);
            }
            else if (a != null) {
                @SuppressWarnings("unchecked")
                XmlAdapter<Object, Object> adapter = a.value().getConstructor().newInstance();
                Reflect.on(result).set(childName, adapter.unmarshal(childElement.getTextContent().trim()));
            }
            else {
                Reflect.on(result).set(childName, Convert.convert(childElement.getTextContent().trim(), childType));
            }
        }
    }

    private static void unmarshalList0(List<Object> result, Element element, String name, Class<?> type) throws Exception {
        if (result == null)
            return;

        NodeList list = element.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node item = list.item(i);

            if (item.getNodeType() == Node.ELEMENT_NODE) {
                if (name.equals(((Element) item).getTagName()) || name.equals(((Element) item).getLocalName())) {
                    Object o = Reflect.on(type).create().get();
                    unmarshal0(o, (Element) item);
                    result.add(o);
                }
            }
        }
    }

    private static Element child(Element element, String name) {
        NodeList list = element.getChildNodes();

        for (int i = 0; i < list.getLength(); i++) {
            Node item = list.item(i);

            if (item.getNodeType() == Node.ELEMENT_NODE)
                if (name.equals(((Element) item).getTagName()) || name.equals(((Element) item).getLocalName()))
                    return (Element) item;
        }

        return null;
    }

    public static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

            // -----------------------------------------------------------------
            // [JOOX #136] FIX START: Prevent OWASP attack vectors
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            }
            catch (ParserConfigurationException ignore) {}

            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            }
            catch (ParserConfigurationException ignore) {}

            try {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            }
            catch (ParserConfigurationException ignore) {}

            // [JOOX #149] Not implemented on Android
            try {
                factory.setXIncludeAware(false);
            }
            catch (UnsupportedOperationException ignore) {}

            factory.setExpandEntityReferences(false);
            // [JOOX #136] FIX END
            // -----------------------------------------------------------------

            // [JOOX #9] [JOOX #107] In order to take advantage of namespace-related DOM
            // features, the internal builder should be namespace-aware
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();

            return builder;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Appends a <code>second</code> JAXB annotated object to a
     * <code>first</code> one using Maven's
     * <code>combine.children="append"</code> semantics.
     *
     * @return The modified <code>first</code> argument.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T> T append(T first, T second) {
        if (first == null)
            return second;
        if (second == null)
            return first;

        Class<T> klass = (Class<T>) first.getClass();
        if (klass != second.getClass())
            throw new IllegalArgumentException("Can only append identical types");
        // [#8527] support enum types
        else if (klass.isEnum())
            return first;

        // We're assuming that XJC generated objects are all in the same package
        Package pkg = klass.getPackage();
        try {
            T defaults = klass.getConstructor().newInstance();

            for (Method setter : klass.getMethods()) {
                if (setter.getName().startsWith("set")) {
                    Method getter;

                    try {
                        getter = klass.getMethod("get" + setter.getName().substring(3));
                    }
                    catch (NoSuchMethodException e) {
                        getter = klass.getMethod("is" + setter.getName().substring(3));
                    }

                    Class<?> childType = setter.getParameterTypes()[0];
                    Object firstChild = getter.invoke(first);
                    Object secondChild = getter.invoke(second);
                    Object defaultChild = getter.invoke(defaults);

                    if (Collection.class.isAssignableFrom(childType))
                        ((List) firstChild).addAll((List) secondChild);
                    else if (secondChild != null && (firstChild == null || firstChild.equals(defaultChild)))
                        setter.invoke(first, secondChild);
                    else if (secondChild != null && pkg == childType.getPackage())
                        append(firstChild, secondChild);
                    else
                        ; // All other types cannot be merged
                }
            }
        }
        catch (Exception e) {
            throw new ReflectException(e);
        }

        return first;
    }
}
