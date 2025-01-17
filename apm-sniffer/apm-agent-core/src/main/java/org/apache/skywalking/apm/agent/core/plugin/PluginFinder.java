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
 *
 */

package org.apache.skywalking.apm.agent.core.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.skywalking.apm.agent.core.plugin.bytebuddy.AbstractJunction;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.IndirectMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.ProtectiveShieldMatcher;

import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * The <code>PluginFinder</code> represents a finder , which assist to find the one from the given {@link
 * AbstractClassEnhancePluginDefine} list.
 */
public class PluginFinder {
    // 指名道姓匹配的
    // 为什么是 <String, List> ?
    // 对于同一个类, 可能有多个插件都要进行字节码增强
    //
    // key => 目标类
    // value => 字节码增强插件列表
    private final Map<String, LinkedList<AbstractClassEnhancePluginDefine>> nameMatchDefine = new HashMap<String, LinkedList<AbstractClassEnhancePluginDefine>>();
    // 间接匹配的
    // 间接匹配因为无法匹配到具体的类, 只能根据条件进行匹配, 因此是个 List
    private final List<AbstractClassEnhancePluginDefine> signatureMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();
    // jdk 类库中的类
    // 监控的是 jdk 类库中的类, 监控线程传输的东西
    private final List<AbstractClassEnhancePluginDefine> bootstrapClassMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();
    private static boolean IS_PLUGIN_INIT_COMPLETED = false;

    public PluginFinder(List<AbstractClassEnhancePluginDefine> plugins) {
        // 对插件进行分类
        for (AbstractClassEnhancePluginDefine plugin : plugins) {
            ClassMatch match = plugin.enhanceClass();

            if (match == null) {
                continue;
            }

            if (match instanceof NameMatch) {
                NameMatch nameMatch = (NameMatch) match;
                LinkedList<AbstractClassEnhancePluginDefine> pluginDefines = nameMatchDefine.get(nameMatch.getClassName());
                if (pluginDefines == null) {
                    pluginDefines = new LinkedList<AbstractClassEnhancePluginDefine>();
                    nameMatchDefine.put(nameMatch.getClassName(), pluginDefines);
                }
                pluginDefines.add(plugin);
            } else {
                signatureMatchDefine.add(plugin);
            }

            if (plugin.isBootstrapInstrumentation()) {
                bootstrapClassMatchDefine.add(plugin);
            }
        }
    }

    // 查找所有能够对指定类型生效的插件
    //   1. 从命名插件里找
    //   2. 从签名插件里匹配
    public List<AbstractClassEnhancePluginDefine> find(TypeDescription typeDescription) {
        List<AbstractClassEnhancePluginDefine> matchedPlugins = new LinkedList<AbstractClassEnhancePluginDefine>();
        String typeName = typeDescription.getTypeName();
        if (nameMatchDefine.containsKey(typeName)) {
            matchedPlugins.addAll(nameMatchDefine.get(typeName));
        }

        for (AbstractClassEnhancePluginDefine pluginDefine : signatureMatchDefine) {
            IndirectMatch match = (IndirectMatch) pluginDefine.enhanceClass();
            if (match.isMatch(typeDescription)) {
                matchedPlugins.add(pluginDefine);
            }
        }

        return matchedPlugins;
    }

    // 告诉 Byte Buddy 需要拦截的类
    public ElementMatcher<? super TypeDescription> buildMatch() {
        // 查找所有符合的类
        ElementMatcher.Junction judge = new AbstractJunction<NamedElement>() {
            @Override
            public boolean matches(NamedElement target) {
                return nameMatchDefine.containsKey(target.getActualName());
            }
        };
        // 过滤接口
        judge = judge.and(not(isInterface()));
        // 通过 or 连接所有的类, 形成一个巨大的判断条件
        for (AbstractClassEnhancePluginDefine define : signatureMatchDefine) {
            ClassMatch match = define.enhanceClass();
            if (match instanceof IndirectMatch) {
                judge = judge.or(((IndirectMatch) match).buildJunction());
            }
        }
        return new ProtectiveShieldMatcher(judge);
    }

    public List<AbstractClassEnhancePluginDefine> getBootstrapClassMatchDefine() {
        return bootstrapClassMatchDefine;
    }

    public static void pluginInitCompleted() {
        IS_PLUGIN_INIT_COMPLETED = true;
    }

    public static boolean isPluginInitCompleted() {
        return IS_PLUGIN_INIT_COMPLETED;
    }
}
