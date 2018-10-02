package org.jboss.protean.arc.processor;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import javax.enterprise.inject.Model;
import javax.enterprise.inject.spi.DefinitionException;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationTarget.Kind;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.ClassInfo.NestingType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.jboss.logging.Logger.Level;

/**
 *
 * @author Martin Kouba
 */
public class BeanDeployment {

    private static final Logger LOGGER = Logger.getLogger(BeanDeployment.class);

    private final IndexView index;

    private final Map<DotName, ClassInfo> qualifiers;

    private final Map<DotName, ClassInfo> interceptorBindings;

    private final Map<DotName, StereotypeInfo> stereotypes;

    private final List<BeanInfo> beans;

    private final List<InterceptorInfo> interceptors;

    private final List<ObserverInfo> observers;

    private final BeanResolver beanResolver;

    private final InterceptorResolver interceptorResolver;

    private final List<BiFunction<AnnotationTarget, Collection<AnnotationInstance>, Collection<AnnotationInstance>>> annotationTransformers;

    BeanDeployment(IndexView index, Collection<DotName> additionalBeanDefiningAnnotations,
            List<BiFunction<AnnotationTarget, Collection<AnnotationInstance>, Collection<AnnotationInstance>>> annotationTransformers) {
        long start = System.currentTimeMillis();
        this.index = index;
        this.qualifiers = findQualifiers(index);
        // TODO interceptor bindings are transitive!!!
        this.interceptorBindings = findInterceptorBindings(index);
        this.stereotypes = findStereotypes(index, interceptorBindings);
        this.interceptors = findInterceptors();
        this.beanResolver = new BeanResolver(this);
        List<ObserverInfo> observers = new ArrayList<>();
        this.beans = findBeans(initBeanDefiningAnnotations(additionalBeanDefiningAnnotations, stereotypes), observers);
        this.observers = observers;
        this.interceptorResolver = new InterceptorResolver(this);
        this.annotationTransformers = annotationTransformers;
        LOGGER.infof("Build deployment created in %s ms", System.currentTimeMillis() - start);
    }

    public Collection<BeanInfo> getBeans() {
        return beans;
    }

    Collection<ObserverInfo> getObservers() {
        return observers;
    }

    Collection<InterceptorInfo> getInterceptors() {
        return interceptors;
    }

    IndexView getIndex() {
        return index;
    }

    BeanResolver getBeanResolver() {
        return beanResolver;
    }

    InterceptorResolver getInterceptorResolver() {
        return interceptorResolver;
    }

    ClassInfo getQualifier(DotName name) {
        return qualifiers.get(name);
    }

    ClassInfo getInterceptorBinding(DotName name) {
        return interceptorBindings.get(name);
    }

    StereotypeInfo getStereotype(DotName name) {
        return stereotypes.get(name);
    }

    Collection<AnnotationInstance> getAnnotations(AnnotationTarget target) {
        Collection<AnnotationInstance> annotations = null;
        switch (target.kind()) {
            case CLASS:
                annotations = target.asClass().classAnnotations();
                break;
            case METHOD:
                annotations = target.asMethod().annotations();
            case FIELD:
                annotations = target.asField().annotations();
            default:
                throw new UnsupportedOperationException();
        }
        if (annotationTransformers == null || annotationTransformers.isEmpty()) {
            return annotations;
        }
        for (BiFunction<AnnotationTarget, Collection<AnnotationInstance>, Collection<AnnotationInstance>> transformer : annotationTransformers) {
            annotations = transformer.apply(target, annotations);
        }
        return annotations;
    }

    void init() {
        long start = System.currentTimeMillis();
        for (BeanInfo bean : beans) {
            bean.init();
        }
        for (InterceptorInfo interceptor : interceptors) {
            interceptor.init();
        }
        LOGGER.infof("Bean deployment initialized in %s ms", System.currentTimeMillis() - start);
    }

    static Map<DotName, ClassInfo> findQualifiers(IndexView index) {
        Map<DotName, ClassInfo> qualifiers = new HashMap<>();
        for (AnnotationInstance qualifier : index.getAnnotations(DotNames.QUALIFIER)) {
            qualifiers.put(qualifier.target().asClass().name(), qualifier.target().asClass());
        }
        return qualifiers;
    }

    static Map<DotName, ClassInfo> findInterceptorBindings(IndexView index) {
        Map<DotName, ClassInfo> bindings = new HashMap<>();
        for (AnnotationInstance binding : index.getAnnotations(DotNames.INTERCEPTOR_BINDING)) {
            bindings.put(binding.target().asClass().name(), binding.target().asClass());
        }
        return bindings;
    }

    static Map<DotName, StereotypeInfo> findStereotypes(IndexView index, Map<DotName, ClassInfo> interceptorBindings) {
        Map<DotName, StereotypeInfo> stereotypes = new HashMap<>();
        for (AnnotationInstance stereotype : index.getAnnotations(DotNames.STEREOTYPE)) {
            ClassInfo stereotypeClass = index.getClassByName(stereotype.target().asClass().name());
            if (stereotypeClass != null) {

                boolean isAlternative = false;
                ScopeInfo scope = null;
                List<AnnotationInstance> bindings = new ArrayList<>();

                for (AnnotationInstance annotation : stereotypeClass.classAnnotations()) {
                    if (annotation.name().equals(DotNames.ALTERNATIVE)) {
                        isAlternative = true;
                    } else if (interceptorBindings.containsKey(annotation.name())) {
                        bindings.add(annotation);
                    } else if (scope == null) {
                        scope = ScopeInfo.from(annotation.name());
                    }
                }
                stereotypes.put(stereotype.target().asClass().name(), new StereotypeInfo(scope, bindings, isAlternative, stereotypeClass));
            }
        }
        return stereotypes;
    }

    private List<BeanInfo> findBeans(List<DotName> beanDefiningAnnotations, List<ObserverInfo> observers) {

        Set<DotName> processed = new HashSet<>();
        Set<ClassInfo> beanClasses = new HashSet<>();
        Set<MethodInfo> producerMethods = new HashSet<>();
        Set<MethodInfo> disposerMethods = new HashSet<>();
        Set<FieldInfo> producerFields = new HashSet<>();
        Set<MethodInfo> observerMethods = new HashSet<>();

        for (DotName beanDefiningAnnotation : beanDefiningAnnotations) {
            for (AnnotationInstance annotation : index.getAnnotations(beanDefiningAnnotation)) {
                if (Kind.CLASS.equals(annotation.target().kind())) {

                    ClassInfo beanClass = annotation.target().asClass();
                    if (!processed.add(beanClass.name())) {
                        continue;
                    }

                    if (beanClass.annotations().containsKey(DotNames.INTERCEPTOR)) {
                        // Skip interceptors
                        continue;
                    }
                    if (beanClass.nestingType().equals(NestingType.ANONYMOUS) || beanClass.nestingType().equals(NestingType.LOCAL)
                            || (beanClass.nestingType().equals(NestingType.INNER) && !Modifier.isStatic(beanClass.flags()))
                            || Modifier.isInterface(beanClass.flags())) {
                        // Skip interfaces, annonymous, local and inner classes
                        continue;
                    }
                    beanClasses.add(beanClass);

                    for (MethodInfo method : beanClass.methods()) {
                        if (method.hasAnnotation(DotNames.PRODUCES)) {
                            producerMethods.add(method);
                        } else if (method.hasAnnotation(DotNames.DISPOSES)) {
                            disposerMethods.add(method);
                        } else if (method.hasAnnotation(DotNames.OBSERVES)) {
                            observerMethods.add(method);
                        }
                    }
                    for (FieldInfo field : beanClass.fields()) {
                        if (field.annotations().stream().anyMatch(a -> a.name().equals(DotNames.PRODUCES))) {
                            producerFields.add(field);
                        }
                    }
                }
            }
        }

        // Build metadata for typesafe resolution
        List<BeanInfo> beans = new ArrayList<>();
        Map<ClassInfo, BeanInfo> beanClassToBean = new HashMap<>();
        for (ClassInfo beanClass : beanClasses) {
            BeanInfo classBean = Beans.createClassBean(beanClass, this);
            beans.add(classBean);
            beanClassToBean.put(beanClass, classBean);
        }

        List<DisposerInfo> disposers = new ArrayList<>();
        for (MethodInfo disposerMethod : disposerMethods) {
            BeanInfo declaringBean = beanClassToBean.get(disposerMethod.declaringClass());
            if (declaringBean != null) {
                disposers.add(new DisposerInfo(declaringBean, disposerMethod, Injection.forDisposer(disposerMethod, this)));
            }
        }

        for (MethodInfo producerMethod : producerMethods) {
            BeanInfo declaringBean = beanClassToBean.get(producerMethod.declaringClass());
            if (declaringBean != null) {
                beans.add(Beans.createProducerMethod(producerMethod, declaringBean, this, findDisposer(declaringBean, producerMethod, disposers)));
            }
        }

        for (FieldInfo producerField : producerFields) {
            BeanInfo declaringBean = beanClassToBean.get(producerField.declaringClass());
            if (declaringBean != null) {
                beans.add(Beans.createProducerField(producerField, declaringBean, this, findDisposer(declaringBean, producerField, disposers)));
            }
        }

        for (MethodInfo observerMethod : observerMethods) {
            BeanInfo declaringBean = beanClassToBean.get(observerMethod.declaringClass());
            if (declaringBean != null) {
                observers.add(new ObserverInfo(declaringBean, observerMethod, Injection.forObserver(observerMethod, this)));
            }
        }

        if (LOGGER.isDebugEnabled()) {
            for (BeanInfo bean : beans) {
                LOGGER.logf(Level.DEBUG, "Created %s", bean);
            }
        }
        return beans;
    }

    private DisposerInfo findDisposer(BeanInfo declaringBean, AnnotationTarget annotationTarget, List<DisposerInfo> disposers) {
        List<DisposerInfo> found = new ArrayList<>();
        Type beanType;
        Set<AnnotationInstance> qualifiers;
        if (Kind.FIELD.equals(annotationTarget.kind())) {
            beanType = annotationTarget.asField().type();
            qualifiers = annotationTarget.asField().annotations().stream().filter(a -> getQualifier(a.name()) != null).collect(Collectors.toSet());
        } else if (Kind.METHOD.equals(annotationTarget.kind())) {
            beanType = annotationTarget.asMethod().returnType();
            qualifiers = annotationTarget.asMethod().annotations().stream().filter(a -> Kind.METHOD.equals(a.target().kind()) && getQualifier(a.name()) != null)
                    .collect(Collectors.toSet());
        } else {
            throw new RuntimeException("Unsupported annotation target: " + annotationTarget);
        }
        for (DisposerInfo disposer : disposers) {
            if (disposer.getDeclaringBean().equals(declaringBean)) {
                boolean hasQualifier = true;
                for (AnnotationInstance qualifier : qualifiers) {
                    if (!Beans.hasQualifier(getQualifier(qualifier.name()), qualifier, null)) {
                        hasQualifier = false;
                    }
                }
                if (hasQualifier && beanResolver.matches(beanType, disposer.getDisposerMethod().parameters().get(disposer.getDisposedParameter().position()))) {
                    found.add(disposer);
                }

            }
        }
        if (found.size() > 1) {
            throw new DefinitionException("Multiple disposer methods found for " + annotationTarget);
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private List<InterceptorInfo> findInterceptors() {
        Set<ClassInfo> interceptorClasses = new HashSet<>();
        for (AnnotationInstance annotation : index.getAnnotations(DotNames.INTERCEPTOR)) {
            if (Kind.CLASS.equals(annotation.target().kind())) {
                interceptorClasses.add(annotation.target().asClass());
            }
        }
        List<InterceptorInfo> interceptors = new ArrayList<>();
        for (ClassInfo interceptorClass : interceptorClasses) {
            interceptors.add(Interceptors.createInterceptor(interceptorClass, this));
        }
        if (LOGGER.isDebugEnabled()) {
            for (InterceptorInfo interceptor : interceptors) {
                LOGGER.logf(Level.DEBUG, "Created %s", interceptor);
            }
        }
        return interceptors;
    }

    private List<DotName> initBeanDefiningAnnotations(Collection<DotName> additionalBeanDefiningAnnotationss, Map<DotName, StereotypeInfo> stereotypes) {
        List<DotName> beanDefiningAnnotations = new ArrayList<>();
        for (ScopeInfo scope : ScopeInfo.values()) {
            beanDefiningAnnotations.add(scope.getDotName());
        }
        if (additionalBeanDefiningAnnotationss != null) {
            beanDefiningAnnotations.addAll(additionalBeanDefiningAnnotationss);
        }
        beanDefiningAnnotations.addAll(stereotypes.keySet());
        beanDefiningAnnotations.add(DotName.createSimple(Model.class.getName()));
        return beanDefiningAnnotations;
    }

}
