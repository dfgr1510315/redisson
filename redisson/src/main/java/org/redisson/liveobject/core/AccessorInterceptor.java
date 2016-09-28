/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.liveobject.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;

import org.redisson.RedissonBlockingDeque;
import org.redisson.RedissonBlockingQueue;
import org.redisson.RedissonDeque;
import org.redisson.RedissonList;
import org.redisson.RedissonMap;
import org.redisson.RedissonQueue;
import org.redisson.RedissonReference;
import org.redisson.RedissonSet;
import org.redisson.RedissonSortedSet;
import org.redisson.api.RLiveObject;
import org.redisson.api.RMap;
import org.redisson.api.RObject;
import org.redisson.api.RedissonClient;
import org.redisson.api.annotation.REntity;
import org.redisson.api.annotation.REntity.TransformationMode;
import org.redisson.api.annotation.RId;
import org.redisson.api.annotation.RObjectField;
import org.redisson.client.codec.Codec;
import org.redisson.codec.CodecProvider;
import org.redisson.liveobject.misc.Introspectior;
import org.redisson.liveobject.resolver.NamingScheme;
import org.redisson.misc.RedissonObjectFactory;

import io.netty.util.internal.PlatformDependent;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;

/**
 * This class is going to be instantiated and becomes a <b>static</b> field of
 * the proxied target class. That is one instance of this class per proxied
 * class.
 *
 * @author Rui Gu (https://github.com/jackygurui)
 */
public class AccessorInterceptor {

    private final RedissonClient redisson;
    private final CodecProvider codecProvider;
    private final ConcurrentMap<String, NamingScheme> namingSchemeCache = PlatformDependent.newConcurrentHashMap();
    private static final LinkedHashMap<Class<?>, Class<? extends RObject>> supportedClassMapping;

    public AccessorInterceptor(RedissonClient redisson) {
        this.redisson = redisson;
        this.codecProvider = redisson.getCodecProvider();
    }

    static {
        supportedClassMapping = new LinkedHashMap<Class<?>, Class<? extends RObject>>();
        supportedClassMapping.put(SortedSet.class,      RedissonSortedSet.class);
        supportedClassMapping.put(Set.class,            RedissonSet.class);
        supportedClassMapping.put(ConcurrentMap.class,  RedissonMap.class);
        supportedClassMapping.put(Map.class,            RedissonMap.class);
        supportedClassMapping.put(BlockingDeque.class,  RedissonBlockingDeque.class);
        supportedClassMapping.put(Deque.class,          RedissonDeque.class);
        supportedClassMapping.put(BlockingQueue.class,  RedissonBlockingQueue.class);
        supportedClassMapping.put(Queue.class,          RedissonQueue.class);
        supportedClassMapping.put(List.class,           RedissonList.class);
    }
    
    @RuntimeType
    public Object intercept(@Origin Method method, @SuperCall Callable<?> superMethod,
            @AllArguments Object[] args, @This Object me,
            @FieldValue("liveObjectLiveMap") RMap liveMap) throws Exception {
        if (isGetter(method, getREntityIdFieldName(me))) {
            return ((RLiveObject) me).getLiveObjectId();
        }
        if (isSetter(method, getREntityIdFieldName(me))) {
            ((RLiveObject) me).setLiveObjectId(args[0]);
            return null;
        }

        String fieldName = getFieldName(method);
        Class<?> idFieldType = me.getClass().getSuperclass().getDeclaredField(fieldName).getType();
        
        if (isGetter(method, fieldName)) {
            Object result = liveMap.get(fieldName);
            if (result == null) {
                Class<? extends RObject> mappedClass = getMappedClass(idFieldType);
                if (mappedClass != null) {
                    Codec fieldCodec = getFieldCodec(me.getClass().getSuperclass(), mappedClass, fieldName);
                    NamingScheme fieldNamingScheme = getFieldNamingScheme(me.getClass().getSuperclass(), fieldName, fieldCodec);
                    
                    RObject obj = RedissonObjectFactory
                            .createRObject(redisson,
                                    mappedClass,
                                    fieldNamingScheme.getFieldReferenceName(me.getClass().getSuperclass(),
                                            ((RLiveObject) me).getLiveObjectId(),
                                            mappedClass,
                                            fieldName,
                                            null),
                                    fieldCodec);
                    
                    codecProvider.registerCodec((Class) fieldCodec.getClass(), obj, obj.getCodec());
                    liveMap.fastPut(fieldName,
                            new RedissonReference(obj.getClass(), obj.getName(), obj.getCodec()));
                    
                    return obj;
                }
            }
            
            return result instanceof RedissonReference
                    ? RedissonObjectFactory.fromReference(redisson, (RedissonReference) result, method.getReturnType())
                    : result;
        }
        if (isSetter(method, fieldName)) {
            if (args[0] instanceof RLiveObject) {
                Class<? extends Object> rEntity = args[0].getClass().getSuperclass();
                REntity anno = rEntity.getAnnotation(REntity.class);
                NamingScheme ns = anno.namingScheme()
                        .getDeclaredConstructor(Codec.class)
                        .newInstance(codecProvider.getCodec(anno, (Class) rEntity));
                liveMap.fastPut(fieldName, new RedissonReference(rEntity,
                        ns.getName(rEntity, idFieldType, getREntityIdFieldName(args[0]),
                                ((RLiveObject) args[0]).getLiveObjectId())));
                return me;
            }
            Object arg = args[0];
            if (!(arg instanceof RObject)
                    && (arg instanceof Collection || arg instanceof Map)
                    && TransformationMode.ANNOTATION_BASED
                            .equals(me.getClass().getSuperclass()
                            .getAnnotation(REntity.class).fieldTransformation())) {
                Class<? extends RObject> mappedClass = getMappedClass(arg.getClass());
                if (mappedClass != null) {
                    Codec fieldCodec = getFieldCodec(me.getClass().getSuperclass(), mappedClass, fieldName);
                    NamingScheme fieldNamingScheme = getFieldNamingScheme(me.getClass().getSuperclass(), fieldName, fieldCodec);
                    
                    RObject obj = RedissonObjectFactory
                            .createRObject(redisson,
                                    mappedClass,
                                    fieldNamingScheme.getFieldReferenceName(me.getClass().getSuperclass(),
                                            ((RLiveObject) me).getLiveObjectId(),
                                            mappedClass,
                                            fieldName,
                                            arg),
                                    fieldCodec);
                    
                    if (obj instanceof Collection) {
                        ((Collection) obj).addAll((Collection) arg);
                    } else {
                        ((Map) obj).putAll((Map) arg);
                    }
                    arg = obj;
                }
            }
            
            if (arg instanceof RObject) {
                RObject ar = (RObject) arg;
                Codec codec = ar.getCodec();
                codecProvider.registerCodec((Class) codec.getClass(), ar, codec);
                liveMap.fastPut(fieldName,
                        new RedissonReference(ar.getClass(), ar.getName(), codec));
                return me;
            }
            liveMap.fastPut(fieldName, args[0]);
            return me;
        }
        return superMethod.call();
    }

    private String getFieldName(Method method) {
        return method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4);
    }

    private boolean isGetter(Method method, String fieldName) {
        return method.getName().startsWith("get")
                && method.getName().endsWith(getFieldNameSuffix(fieldName));
    }

    private boolean isSetter(Method method, String fieldName) {
        return method.getName().startsWith("set")
                && method.getName().endsWith(getFieldNameSuffix(fieldName));
    }

    /**
     * WARNING: rEntity has to be the class of @This object.
     */
    private Codec getFieldCodec(Class<?> rEntity, Class<? extends RObject> rObjectClass, String fieldName) throws Exception {
        Field field = rEntity.getDeclaredField(fieldName);
        if (field.isAnnotationPresent(RObjectField.class)) {
            RObjectField anno = field.getAnnotation(RObjectField.class);
            return codecProvider.getCodec(anno, rEntity, rObjectClass, fieldName);
        } else {
            REntity anno = rEntity.getAnnotation(REntity.class);
            return codecProvider.getCodec(anno, (Class) rEntity);
        }
    }
    
    /**
     * WARNING: rEntity has to be the class of @This object.
     */
    private NamingScheme getFieldNamingScheme(Class<?> rEntity, String fieldName, Codec c) throws Exception {
        if (!namingSchemeCache.containsKey(fieldName)) {
            REntity anno = rEntity.getAnnotation(REntity.class);
            namingSchemeCache.putIfAbsent(fieldName, anno.namingScheme()
                    .getDeclaredConstructor(Codec.class)
                    .newInstance(c));
        }
        return namingSchemeCache.get(fieldName);
    }
    
    private static String getFieldNameSuffix(String fieldName) {
        return fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
    }

    private static String getREntityIdFieldName(Object o) throws Exception {
        return Introspectior
                .getFieldsWithAnnotation(o.getClass().getSuperclass(), RId.class)
                .getOnly()
                .getName();
    }

    private static Class<? extends RObject> getMappedClass(Class<?> cls) {
        for (Entry<Class<?>, Class<? extends RObject>> entrySet : supportedClassMapping.entrySet()) {
            if (entrySet.getKey().isAssignableFrom(cls)) {
                return entrySet.getValue();
            }
        }
        return null;
    }
    
}
