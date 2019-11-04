/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.extension;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;

/**
 * Code generator for Adaptive class
 */
public class AdaptiveClassCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveClassCodeGenerator.class);

    private static final String CLASSNAME_INVOCATION = "org.apache.dubbo.rpc.Invocation";

    private static final String CODE_PACKAGE = "package %s;\n";

    private static final String CODE_IMPORTS = "import %s;\n";

    private static final String CODE_CLASS_DECLARATION = "public class %s$Adaptive implements %s {\n";

    private static final String CODE_METHOD_DECLARATION = "public %s %s(%s) %s {\n%s}\n";

    private static final String CODE_METHOD_ARGUMENT = "%s arg%d";

    private static final String CODE_METHOD_THROWS = "throws %s";

    private static final String CODE_UNSUPPORTED = "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n";

    private static final String CODE_URL_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"url == null\");\n%s url = arg%d;\n";

    private static final String CODE_EXT_NAME_ASSIGNMENT = "String extName = %s;\n";

    private static final String CODE_EXT_NAME_NULL_CHECK = "if(extName == null) "
                    + "throw new IllegalStateException(\"Failed to get extension (%s) name from url (\" + url.toString() + \") use keys(%s)\");\n";

    private static final String CODE_INVOCATION_ARGUMENT_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"invocation == null\"); "
                    + "String methodName = arg%d.getMethodName();\n";


    private static final String CODE_EXTENSION_ASSIGNMENT = "%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n";

    private static final String CODE_EXTENSION_METHOD_INVOKE_ARGUMENT = "arg%d";

    private final Class<?> type;

    private String defaultExtName;

    public AdaptiveClassCodeGenerator(Class<?> type, String defaultExtName) {
        this.type = type;
        this.defaultExtName = defaultExtName;
    }

    /**
     * test if given type has at least one method annotated with <code>SPI</code>
     */
    private boolean hasAdaptiveMethod() {
        return Arrays.stream(type.getMethods()).anyMatch(m -> m.isAnnotationPresent(Adaptive.class));
    }

    /**
     * generate and return class code
     */
    public String generate() {
        // no need to generate adaptive class since there's no adaptive method found.
        // 必须至少一个方法有@Adaptive注解
        if (!hasAdaptiveMethod()) {
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        StringBuilder code = new StringBuilder();
        // 用于拼接一个字符串，格式与java类相同
        code.append(generatePackageInfo());
        // 包名=type的包名，引用（import） ExtensionLoader类
        code.append(generateImports());
        // 生成类代码：public class + type简单名称 + $Adaptive + implements + type全限定名 + {
        code.append(generateClassDeclaration());
        // 例如生成代码：
        // package com.alibaba.dubbo.rpc;
        //import com.alibaba.dubbo.common.extension.ExtensionLoader;
        //public class Protocol$Adaptive implements com.alibaba.dubbo.rpc.Protocol {
        //    // 省略方法代码
        //}

        Method[] methods = type.getMethods();
        for (Method method : methods) {
            // 实现type的每个方法
            code.append(generateMethod(method));
        }
        code.append("}");

        if (logger.isDebugEnabled()) {
            logger.debug(code.toString());
        }
        return code.toString();
    }

    /**
     * generate package info
     */
    private String generatePackageInfo() {
        return String.format(CODE_PACKAGE, type.getPackage().getName());
    }

    /**
     * generate imports
     */
    private String generateImports() {
        return String.format(CODE_IMPORTS, ExtensionLoader.class.getName());
    }

    /**
     * generate class declaration
     */
    private String generateClassDeclaration() {
        return String.format(CODE_CLASS_DECLARATION, type.getSimpleName(), type.getCanonicalName());
    }

    /**
     * generate method not annotated with Adaptive with throwing unsupported exception
     */
    private String generateUnsupported(Method method) {
        return String.format(CODE_UNSUPPORTED, method, type.getName());
    }

    /**
     * get index of parameter with type URL
     */
    private int getUrlTypeIndex(Method method) {
        int urlTypeIndex = -1;
        Class<?>[] pts = method.getParameterTypes();
        for (int i = 0; i < pts.length; ++i) {
            if (pts[i].equals(URL.class)) {
                urlTypeIndex = i;
                break;
            }
        }
        return urlTypeIndex;
    }

    /**
     * generate method declaration
     */
    private String generateMethod(Method method) {
        // 方法返回类型
        String methodReturnType = method.getReturnType().getCanonicalName();
        // 方法名
        String methodName = method.getName();
        // 方法体
        String methodContent = generateMethodContent(method);
        // 方法参数
        String methodArgs = generateMethodArguments(method);
        // 方法抛出异常
        String methodThrows = generateMethodThrows(method);
        // 组成方法字符串
        return String.format(CODE_METHOD_DECLARATION, methodReturnType, methodName, methodArgs, methodThrows, methodContent);
    }

    /**
     * generate method arguments
     */
    private String generateMethodArguments(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length)
                        .mapToObj(i -> String.format(CODE_METHOD_ARGUMENT, pts[i].getCanonicalName(), i))
                        .collect(Collectors.joining(", "));
    }

    /**
     * generate method throws
     */
    private String generateMethodThrows(Method method) {
        Class<?>[] ets = method.getExceptionTypes();
        if (ets.length > 0) {
            String list = Arrays.stream(ets).map(Class::getCanonicalName).collect(Collectors.joining(", "));
            return String.format(CODE_METHOD_THROWS, list);
        } else {
            return "";
        }
    }

    /**
     * generate method URL argument null check
     */
    private String generateUrlNullCheck(int index) {
        return String.format(CODE_URL_NULL_CHECK, index, URL.class.getName(), index);
    }

    /**
     * generate method content
     */
    private String generateMethodContent(Method method) {
        Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
        StringBuilder code = new StringBuilder(512);
        // 如果方法没有Adaptive 注解
        // 生成的方法只会抛出一个异常，如
        // throw new UnsupportedOperationException(
        //     "method public abstract void com.alibaba.dubbo.rpc.Protocol.destroy() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
        if (adaptiveAnnotation == null) {
            return generateUnsupported(method);
        } else {
            // 获取参数中URL类型参数的index
            int urlTypeIndex = getUrlTypeIndex(method);

            // found parameter in URL type
            if (urlTypeIndex != -1) {
                // Null Point check
                // url类型参数非空判断
                // 如：if (arg1 == null)
                //        throw new IllegalArgumentException("url == null");
                //    com.alibaba.dubbo.common.URL url = arg1;
                code.append(generateUrlNullCheck(urlTypeIndex));
            } else {
                // did not find parameter in URL type
                // 循环检测方法每个参数是否有一个getUrl的方法
                // 2.方法中无这样的参数，而是在参数集合的属性中
                // 如： if (arg0 == null)
                //          throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");
                //     if (arg0.getUrl() == null)
                //          throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");
                //     com.alibaba.dubbo.common.URL url = arg0.getUrl();
                code.append(generateUrlAssignmentIndirectly(method));
            }

            // 若 value 为非空数组，直接获取数组内容即可。若 value 为空数组，则需进行额外处理。
            // 处理过程是将类名转换为字符数组，然后遍历字符数组，并将字符放入 StringBuilder 中。
            // 若字符为大写字母，则向 StringBuilder 中添加点号，随后将字符变为小写存入 StringBuilder 中
            String[] value = getMethodAdaptiveValue(adaptiveAnnotation);

            // 是否含有Invocation类型的参数
            boolean hasInvocation = hasInvocationArgument(method);

            // 生成Invocation类型参数的判空逻辑
            code.append(generateInvocationArgumentNullCheck(method));

            code.append(generateExtNameAssignment(value, hasInvocation));
            // check extName == null?
            code.append(generateExtNameNullCheck(value));

            code.append(generateExtensionAssignment());

            // return statement
            code.append(generateReturnAndInvocation(method));
        }

        return code.toString();
    }

    /**
     * generate code for variable extName null check
     */
    private String generateExtNameNullCheck(String[] value) {
        return String.format(CODE_EXT_NAME_NULL_CHECK, type.getName(), Arrays.toString(value));
    }

    /**
     * generate extName assigment code
     */
    private String generateExtNameAssignment(String[] value, boolean hasInvocation) {
        // TODO: refactor it
        String getNameCode = null;
        // 此处循环目的是生成从 URL 中获取拓展名的代码，生成的代码会赋值给 getNameCode 变量。注意这
        // 个循环的遍历顺序是由后向前遍历的。
        for (int i = value.length - 1; i >= 0; --i) {
            // 当 i 为最后一个元素的坐标时
            if (i == value.length - 1) {
                // @SPI value不为空
                if (null != defaultExtName) {
                    // protocol 是 url 的一部分，可通过 getProtocol 方法获取，其他的则是从
                    // URL 参数中获取。因为获取方式不同，所以这里要判断 value[i] 是否为 protocol
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            // 生成的代码功能等价于下面的代码：
                            //   url.getMethodParameter(methodName, value[i], defaultExtName)
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            // 生成的代码功能等价于下面的代码：
                            //   url.getParameter(value[i], defaultExtName)
                            getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                        }
                    } else {
                        // 生成的代码功能等价于下面的代码：
                        //   ( url.getProtocol() == null ? defaultExtName : url.getProtocol() )
                        getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                    }
                } else {
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            // 生成的代码功能等价于下面的代码：
                            //   url.getParameter(value[i])
                            getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                        }
                    } else {
                        // 生成从 url 中获取协议的代码，比如 "dubbo"
                        getNameCode = "url.getProtocol()";
                    }
                }
            } else {
                if (!"protocol".equals(value[i])) {
                    if (hasInvocation) {
                        getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                    } else {
                        // 生成的代码功能等价于下面的代码：
                        //   url.getParameter(value[i], getNameCode)
                        getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                    }
                } else {
                    getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                }
            }
        }

        // 生成 extName 赋值代码
        return String.format(CODE_EXT_NAME_ASSIGNMENT, getNameCode);
    }

    /**
     * @return
     */
    private String generateExtensionAssignment() {
        return String.format(CODE_EXTENSION_ASSIGNMENT, type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
    }

    /**
     * generate method invocation statement and return it if necessary
     */
    private String generateReturnAndInvocation(Method method) {
        String returnStatement = method.getReturnType().equals(void.class) ? "" : "return ";

        String args = IntStream.range(0, method.getParameters().length)
                .mapToObj(i -> String.format(CODE_EXTENSION_METHOD_INVOKE_ARGUMENT, i))
                .collect(Collectors.joining(", "));

        return returnStatement + String.format("extension.%s(%s);\n", method.getName(), args);
    }

    /**
     * test if method has argument of type <code>Invocation</code>
     */
    private boolean hasInvocationArgument(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return Arrays.stream(pts).anyMatch(p -> CLASSNAME_INVOCATION.equals(p.getName()));
    }

    /**
     * generate code to test argument of type <code>Invocation</code> is null
     */
    private String generateInvocationArgumentNullCheck(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length).filter(i -> CLASSNAME_INVOCATION.equals(pts[i].getName()))
                        .mapToObj(i -> String.format(CODE_INVOCATION_ARGUMENT_NULL_CHECK, i, i))
                        .findFirst().orElse("");
    }

    /**
     * get value of adaptive annotation or if empty return splitted simple name
     */
    private String[] getMethodAdaptiveValue(Adaptive adaptiveAnnotation) {
        String[] value = adaptiveAnnotation.value();
        // value is not set, use the value generated from class name as the key
        if (value.length == 0) {
            String splitName = StringUtils.camelToSplitName(type.getSimpleName(), ".");
            value = new String[]{splitName};
        }
        return value;
    }

    /**
     * get parameter with type <code>URL</code> from method parameter:
     * <p>
     * test if parameter has method which returns type <code>URL</code>
     * <p>
     * if not found, throws IllegalStateException
     */
    private String generateUrlAssignmentIndirectly(Method method) {
        Class<?>[] pts = method.getParameterTypes();

        // find URL getter method
        for (int i = 0; i < pts.length; ++i) {
            for (Method m : pts[i].getMethods()) {
                String name = m.getName();
                if ((name.startsWith("get") || name.length() > 3)
                        && Modifier.isPublic(m.getModifiers())
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0
                        && m.getReturnType() == URL.class) {
                    return generateGetUrlNullCheck(i, pts[i], name);
                }
            }
        }

        // getter method not found, throw
        throw new IllegalStateException("Failed to create adaptive class for interface " + type.getName()
                        + ": not found url parameter or url attribute in parameters of method " + method.getName());

    }

    /**
     * 1, test if argi is null
     * 2, test if argi.getXX() returns null
     * 3, assign url with argi.getXX()
     */
    private String generateGetUrlNullCheck(int index, Class<?> type, String method) {
        // Null point check
        StringBuilder code = new StringBuilder();
        code.append(String.format("if (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");\n",
                index, type.getName()));
        code.append(String.format("if (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");\n",
                index, method, type.getName(), method));

        code.append(String.format("%s url = arg%d.%s();\n", URL.class.getName(), index, method));
        return code.toString();
    }

}
