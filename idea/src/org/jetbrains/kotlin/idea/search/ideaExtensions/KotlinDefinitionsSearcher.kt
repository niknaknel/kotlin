/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.search.ideaExtensions

import com.intellij.codeInsight.navigation.MethodImplementationsSearch
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.*
import java.util.*

class KotlinDefinitionsSearcher : QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
    override fun execute(queryParameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<PsiElement>): Boolean {
        val consumer = skipDelegatedMethodsConsumer(consumer)
        val element = queryParameters.element
        val scope = queryParameters.scope

        return when (element) {
            is KtClass ->
                processClassImplementations(element, consumer)

            is KtNamedFunction, is KtSecondaryConstructor ->
                processFunctionImplementations(element as KtFunction, scope, consumer)

            is KtProperty ->
                processPropertyImplementations(element, scope, consumer)

            is KtParameter ->
                if (isFieldParameter(element)) processPropertyImplementations(element, scope, consumer) else true

            else -> true
        }
    }

    companion object {

        private fun skipDelegatedMethodsConsumer(baseConsumer: Processor<PsiElement>): Processor<PsiElement> {
            return Processor { element ->
                if (isDelegated(element)) {
                    return@Processor true
                }
                baseConsumer.process(element)
            }
        }

        private fun isDelegated(element: PsiElement): Boolean = element is KtLightMethod && element.isDelegated

        private fun isFieldParameter(parameter: KtParameter): Boolean {
            return runReadAction { KtPsiUtil.getClassIfParameterIsProperty(parameter) != null }
        }

        private fun processClassImplementations(klass: KtClass, consumer: Processor<PsiElement>): Boolean {
            val psiClass = runReadAction { klass.toLightClass() }
            if (psiClass != null) {
                return ContainerUtil.process(ClassInheritorsSearch.search(psiClass, true), consumer)
            }
            return true
        }

        private fun processFunctionImplementations(function: KtFunction, scope: SearchScope, consumer: Processor<PsiElement>): Boolean {
            val psiMethod = runReadAction { LightClassUtil.getLightClassMethod(function) }

            return psiMethod?.let { MethodImplementationsSearch.processImplementations(it, consumer, scope) } ?: true
        }

        private fun processPropertyImplementations(parameter: KtParameter, scope: SearchScope, consumer: Processor<PsiElement>): Boolean {
            val accessorsPsiMethods = runReadAction { LightClassUtil.getLightClassPropertyMethods(parameter) }

            return processPropertyImplementationsMethods(accessorsPsiMethods, scope, consumer)
        }

        private fun processPropertyImplementations(property: KtProperty, scope: SearchScope, consumer: Processor<PsiElement>): Boolean {
            val accessorsPsiMethods = runReadAction { LightClassUtil.getLightClassPropertyMethods(property) }

            return processPropertyImplementationsMethods(accessorsPsiMethods, scope, consumer)
        }

        fun processPropertyImplementationsMethods(accessors: LightClassUtil.PropertyAccessorsPsiMethods, scope: SearchScope, consumer: Processor<PsiElement>): Boolean {
            for (method in accessors) {
                val implementations = ArrayList<PsiMethod>()
                MethodImplementationsSearch.getOverridingMethods(method, implementations, scope)

                for (implementation in implementations) {
                    if (isDelegated(implementation)) continue

                    val mirrorElement = (implementation as? KtLightMethod)?.kotlinOrigin
                    val elementToProcess = when(mirrorElement) {
                        is KtProperty, is KtParameter -> mirrorElement
                        is KtPropertyAccessor -> if (mirrorElement.parent is KtProperty) mirrorElement.parent else implementation
                        else -> implementation
                    }

                    if (!consumer.process(elementToProcess)) {
                        return false
                    }
                }
            }
            return true
        }
    }
}
