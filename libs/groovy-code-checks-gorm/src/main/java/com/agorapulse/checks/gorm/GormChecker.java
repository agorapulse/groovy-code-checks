/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2021 Vladimir Orany.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agorapulse.checks.gorm;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@GroovyASTTransformation(phase = CompilePhase.FINALIZATION)
public class GormChecker extends AbstractASTTransformation {

    private static final Set<String> GORM_INSTANCE_METHOD_NAMES = new HashSet<>(Arrays.asList(
            "instanceOf",
            "lock",
            "mutex",
            "refresh",
            "save",
            "insert",
            "merge",
            "ident",
            "attach",
            "isAttached",
            "discard",
            "delete",
            "isDirty",
            "getDirtyPropertyNames",
            "getPersistentValue",
            "getAssociationId",
            "removeFrom",
            "addTo"
    ));

    private static final Set<String> GORM_STATIC_METHOD_NAMES = new HashSet<>(Arrays.asList(
            "getGormPersistentEntity",
            "getGormDynamicFinders",
            "where",
            "whereLazy",
            "whereAny",
            "findAll",
            "find",
            "saveAll",
            "deleteAll",
            "create",
            "get",
            "read",
            "load",
            "proxy",
            "getAll",
            "createCriteria",
            "withCriteria",
            "lock",
            "merge",
            "count",
            "getCount",
            "exists",
            "list",
            "first",
            "last",
            "findAllWhere",
            "findWhere",
            "findOrCreateWhere",
            "withSession",
            "withDatastoreSession",
            "withNewTransaction",
            "withTransaction",
            "withNewSession",
            "withStatelessSession",
            "executeQuery",
            "executeUpdate",
            "getNamedQuery"
    ));

    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {

        ClassCodeVisitorSupport support = new ClassCodeVisitorSupport() {

            private ClassNode currentClass;

            @Override
            public void visitClass(ClassNode node) {
                this.currentClass = node;
                super.visitClass(node);
                this.currentClass = null;
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                if (GORM_INSTANCE_METHOD_NAMES.contains(call.getMethodAsString()) || GORM_STATIC_METHOD_NAMES.contains(call.getMethodAsString())) {
                    if (isEntityClass(getType(call))) {
                        addError("Calling GORM methods is forbidden!", call);
                    }
                }
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                if (GORM_STATIC_METHOD_NAMES.contains(call.getMethodAsString())) {
                    if (isEntityClass(getType(call))) {
                        addError("Calling GORM methods is forbidden!", call);
                    }
                }
                super.visitStaticMethodCallExpression(call);
            }

            @Override
            protected SourceUnit getSourceUnit() {
                return source;
            }

            private ClassNode getType(MethodCall call) {
                Expression object = (Expression) call.getReceiver();

                if (object.getColumnNumber() < 0 || object.getLineNumber() < 0) {
                    // generated code
                    return null;
                }

                if (object instanceof VariableExpression) {
                    Variable accessedVariable = ((VariableExpression) object).getAccessedVariable();
                    if (accessedVariable != null) {
                        return accessedVariable.getType();
                    }
                }

                if (object instanceof PropertyExpression) {
                    PropertyExpression propertyExpression = (PropertyExpression) object;
                    Expression objectExpression = propertyExpression.getObjectExpression();

                    if (objectExpression instanceof VariableExpression) {
                        // instance field
                        if (((VariableExpression) objectExpression).getName().equals("this") && currentClass != null) {
                            PropertyNode property = currentClass.getProperty(propertyExpression.getPropertyAsString());
                            return property.getField().getType();
                        }
                        return objectExpression.getType();
                    }

                    if (objectExpression instanceof ClassExpression) {
                        // static field
                        ClassNode holder = objectExpression.getType();
                        FieldNode field = holder.getField(propertyExpression.getPropertyAsString());
                        return field.getType();
                    }

                    if (objectExpression != null) {
                        return objectExpression.getType();
                    }
                }

                if (object instanceof MethodCallExpression) {
                    MethodCallExpression anotherCall = (MethodCallExpression) object;
                    Expression objectExpression = anotherCall.getObjectExpression();
                    if (objectExpression instanceof VariableExpression) {
                        VariableExpression variable = (VariableExpression) objectExpression;
                        if (variable.getName().equals("this") && currentClass != null) {
                            // TODO: handle multiple matching methods
                            Optional<MethodNode> method = currentClass.getMethods(anotherCall.getMethodAsString()).stream().findFirst();
                            return method.map(MethodNode::getReturnType).orElse(null);
                        }
                        // TODO: handle multiple matching methods
                        Optional<MethodNode> method = variable.getType().getMethods(anotherCall.getMethodAsString()).stream().findFirst();
                        return method.map(MethodNode::getReturnType).orElse(null);
                    } else {
                        System.out.println(objectExpression);
                    }
                }

                if (object instanceof StaticMethodCallExpression) {
                    StaticMethodCallExpression anotherCall = (StaticMethodCallExpression) object;
                    Optional<MethodNode> method = anotherCall.getOwnerType().getMethods(anotherCall.getMethodAsString()).stream().findFirst();
                    return method.map(MethodNode::getReturnType).orElse(null);
                }

                return object.getType();
            }

            private boolean isEntityClass(ClassNode type) {
                if (type == null) {
                    return false;
                }

                Set<ClassNode> allInterfaces = type.getAllInterfaces();

                if (allInterfaces == null) {
                    return false;
                }

                return allInterfaces.stream().anyMatch(i -> {
                    if (i != null && i.getName() != null) {
                        return i.getName().equals("org.grails.datastore.gorm.GormEntityApi");
                    }
                    return false;
                });
            }
        };

        source.getAST().getClasses().forEach(support::visitClass);
    }

}
