/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

@file:Suppress("PRE_RELEASE_CLASS")
package org.jetbrains.webdemo.kotlin.impl

import org.jetbrains.webdemo.kotlin.impl.environment.EnvironmentManager
import java.util.*

object ResolveUtils {

    fun getBindingContext(files: List<KtFile>, project: Project, isJs: Boolean): BindingContext {
        val result = if (isJs) analyzeFileForJs(files, project) else analyzeFileForJvm(files, project)
        val analyzeExhaust = result.getFirst()
        return analyzeExhaust.bindingContext
    }

    fun getGenerationState(files: List<KtFile>, project: Project, compilerConfiguration: CompilerConfiguration): GenerationState {
        val analyzeExhaust = analyzeFileForJvm(files, project).getFirst()
        return GenerationState.Builder(
                project,
                ClassBuilderFactories.BINARIES,
                analyzeExhaust.moduleDescriptor,
                analyzeExhaust.bindingContext,
                files,
                compilerConfiguration
        ).build()
    }

    @Synchronized
    fun analyzeFileForJvm(files: List<KtFile>, project: Project): Pair<AnalysisResult, ComponentProvider> {
        val environment = EnvironmentManager.getEnvironment()
        val trace = CliBindingTrace()
        val configuration = environment.configuration
        configuration.put(JVMConfigurationKeys.ADD_BUILT_INS_FROM_COMPILER_TO_DEPENDENCIES, true)

        val container = TopDownAnalyzerFacadeForJVM.createContainer(
                environment.project,
                files,
                trace,
                configuration,
                { globalSearchScope -> environment.createPackagePartProvider(globalSearchScope) },
                { storageManager, ktFiles -> FileBasedDeclarationProviderFactory(storageManager, ktFiles) },
                TopDownAnalyzerFacadeForJVM.newModuleSearchScope(project, files)
        )

        container.getService(LazyTopDownAnalyzer::class.java).analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files, DataFlowInfo.EMPTY)

        val moduleDescriptor = container.getService(ModuleDescriptor::class.java)
        for (extension in AnalysisHandlerExtension.getInstances(project)) {
            val result = extension.analysisCompleted(project, moduleDescriptor, trace, files)
            if (result != null) break
        }

        return Pair(
                AnalysisResult.success(trace.bindingContext, moduleDescriptor),
                container)
    }

    @Synchronized
    fun analyzeFileForJs(files: List<KtFile>, project: Project): Pair<AnalysisResult, ComponentProvider> {
        val environment = EnvironmentManager.getEnvironment()

        val configuration = environment.configuration.copy()
        configuration.put(JSConfigurationKeys.LIBRARIES, listOf(WrapperSettings.JS_LIB_ROOT!!.toString()))
        val config = JsConfig(project, configuration)

        val module = ContextForNewModule(
                ProjectContext(project),
                Name.special("<" + config.moduleId + ">"),
                JsPlatform.builtIns, null
        )
        module.setDependencies(computeDependencies(module.module, config))

        val trace = CliBindingTrace()

        val providerFactory = FileBasedDeclarationProviderFactory(module.storageManager, files)

        val analyzerAndProvider = createContainerForTopDownAnalyzerForJs(module, trace, EnvironmentManager.languageVersion, providerFactory)

        return Pair(
                TopDownAnalyzerFacadeForJS.analyzeFiles(files, config),
                analyzerAndProvider.second)
    }

    private fun computeDependencies(module: ModuleDescriptorImpl, config: JsConfig): List<ModuleDescriptorImpl> {
        val allDependencies = ArrayList<ModuleDescriptorImpl>()
        allDependencies.add(module)
        config.moduleDescriptors.mapTo(allDependencies) { it }
        allDependencies.add(JsPlatform.builtIns.builtInsModule)
        return allDependencies
    }

    private fun createContainerForTopDownAnalyzerForJs(
            moduleContext: ModuleContext,
            bindingTrace: BindingTrace,
            platformVersion: TargetPlatformVersion,
            declarationProviderFactory: DeclarationProviderFactory
    ): Pair<LazyTopDownAnalyzer, ComponentProvider> {
        val container = composeContainer(
                "TopDownAnalyzerForJs",
                JsPlatform.platformConfigurator.platformSpecificContainer
        ) {
            configureModule(moduleContext, JsPlatform, platformVersion, bindingTrace)
            useInstance(declarationProviderFactory)
            registerSingleton(AnnotationResolverImpl::class.java)
            registerSingleton(FileScopeProviderImpl::class.java)

            CompilerEnvironment.configure(this)

            useInstance(LookupTracker.DO_NOTHING)
            useInstance(LanguageVersionSettingsImpl.DEFAULT)
            registerSingleton(ResolveSession::class.java)
            registerSingleton(LazyTopDownAnalyzer::class.java)
        }

        container.getService(ModuleDescriptorImpl::class.java).initialize(container.getService(KotlinCodeAnalyzer::class.java).getPackageFragmentProvider())

        return Pair(container.getService(LazyTopDownAnalyzer::class.java), container)
    }
}
