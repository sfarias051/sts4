/*******************************************************************************
 * Copyright (c) 2017, 2018 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.boot;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.springframework.ide.vscode.boot.java.handlers.RunningAppProvider;
import org.springframework.ide.vscode.boot.java.utils.SpringLiveHoverWatchdog;
import org.springframework.ide.vscode.boot.jdt.ls.JavaProjectsService;
import org.springframework.ide.vscode.boot.jdt.ls.JavaProjectsServiceWithFallback;
import org.springframework.ide.vscode.boot.jdt.ls.JdtLsProjectCache;
import org.springframework.ide.vscode.boot.metadata.AdHocSpringPropertyIndexProvider;
import org.springframework.ide.vscode.boot.metadata.DefaultSpringPropertyIndexProvider;
import org.springframework.ide.vscode.boot.metadata.SpringPropertyIndex;
import org.springframework.ide.vscode.boot.metadata.SpringPropertyIndexProvider;
import org.springframework.ide.vscode.boot.metadata.types.TypeUtil;
import org.springframework.ide.vscode.boot.metadata.types.TypeUtilProvider;
import org.springframework.ide.vscode.commons.gradle.GradleCore;
import org.springframework.ide.vscode.commons.gradle.GradleProjectCache;
import org.springframework.ide.vscode.commons.gradle.GradleProjectFinder;
import org.springframework.ide.vscode.commons.java.BootProjectUtil;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.java.IJavadocProvider;
import org.springframework.ide.vscode.commons.javadoc.JavaDocProviders;
import org.springframework.ide.vscode.commons.languageserver.java.CompositeJavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.java.CompositeProjectOvserver;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.java.JavadocService;
import org.springframework.ide.vscode.commons.languageserver.java.ProjectObserver;
import org.springframework.ide.vscode.commons.languageserver.jdt.ls.Classpath.CPE;
import org.springframework.ide.vscode.commons.languageserver.util.LSFactory;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.maven.MavenCore;
import org.springframework.ide.vscode.commons.maven.java.MavenProjectCache;
import org.springframework.ide.vscode.commons.maven.java.MavenProjectFinder;
import org.springframework.ide.vscode.commons.util.Assert;
import org.springframework.ide.vscode.commons.util.text.IDocument;

/**
 * Parameters for creating Boot Properties language server
 *
 * @author Alex Boyko
 * @author Kris De Volder
 */
public class BootLanguageServerParams {

	//Shared
	public final JavaProjectFinder projectFinder;
	public final ProjectObserver projectObserver;
	public final SpringPropertyIndexProvider indexProvider;
	public final SpringPropertyIndexProvider adHocIndexProvider;

	//Boot Properies
	public final TypeUtilProvider typeUtilProvider;

	//Boot Java
	public final RunningAppProvider runningAppProvider;
	public final Duration watchDogInterval;

	public BootLanguageServerParams(
			JavaProjectFinder projectFinder,
			ProjectObserver projectObserver,
			SpringPropertyIndexProvider indexProvider,
			SpringPropertyIndexProvider adHocIndexProvider,
			TypeUtilProvider typeUtilProvider,
			RunningAppProvider runningAppProvider,
			Duration watchDogInterval
	) {
		super();
		Assert.isNotNull(projectObserver); // null is bad should be ProjectObserver.NULL
		this.projectFinder = projectFinder;
		this.projectObserver = projectObserver;
		this.indexProvider = indexProvider;
		this.adHocIndexProvider = adHocIndexProvider;
		this.typeUtilProvider = typeUtilProvider;
		this.runningAppProvider = runningAppProvider;
		this.watchDogInterval = watchDogInterval;
	}

	public static LSFactory<BootLanguageServerParams> createDefault() {
		return (SimpleLanguageServer server) -> {
			// Initialize project finders, project caches and project observers
			JavaProjectsService jdtProjectCache = new JavaProjectsServiceWithFallback(
					server,
					new JdtLsProjectCache(server),
					() -> createFallbackProjectCache(server)
			);
			DefaultSpringPropertyIndexProvider indexProvider = new DefaultSpringPropertyIndexProvider(jdtProjectCache, jdtProjectCache);
			SpringPropertyIndexProvider adHocProvider = new AdHocSpringPropertyIndexProvider(jdtProjectCache, jdtProjectCache, server.getWorkspaceService().getFileObserver());
			indexProvider.setProgressService(server.getProgressService());

			return new BootLanguageServerParams(
					jdtProjectCache.filter(BootProjectUtil::isBootProject),
					jdtProjectCache,
					indexProvider,
					adHocProvider,
					(IDocument doc) -> new TypeUtil(jdtProjectCache.find(new TextDocumentIdentifier(doc.getUri()))),
					RunningAppProvider.createDefault(server),
					SpringLiveHoverWatchdog.DEFAULT_INTERVAL
			);
		};
	}

	private static JavaProjectsService createFallbackProjectCache(SimpleLanguageServer server) {
		CompositeJavaProjectFinder javaProjectFinder = new CompositeJavaProjectFinder();

		JavadocService javadocService = (uri, cpe) -> JavaDocProviders.createFor(cpe);

		MavenProjectCache mavenProjectCache = new MavenProjectCache(server, MavenCore.getDefault(), true, Paths.get(IJavaProject.PROJECT_CACHE_FOLDER), javadocService);
		javaProjectFinder.addJavaProjectFinder(new MavenProjectFinder(mavenProjectCache));

		GradleProjectCache gradleProjectCache = new GradleProjectCache(server, GradleCore.getDefault(), true, Paths.get(IJavaProject.PROJECT_CACHE_FOLDER), javadocService);
		javaProjectFinder.addJavaProjectFinder(new GradleProjectFinder(gradleProjectCache));

		CompositeProjectOvserver projectObserver = new CompositeProjectOvserver(Arrays.asList(mavenProjectCache, gradleProjectCache));

		return new JavaProjectsService() {

			@Override
			public void removeListener(Listener listener) {
				projectObserver.removeListener(listener);
			}

			@Override
			public void addListener(Listener listener) {
				projectObserver.addListener(listener);
			}

			@Override
			public Optional<IJavaProject> find(TextDocumentIdentifier doc) {
				return javaProjectFinder.find(doc);
			}

			@Override
			public IJavadocProvider javadocProvider(String projectUri, CPE cpe) {
				return javadocService.javadocProvider(projectUri, cpe);
			}
		};
	}

	public static LSFactory<BootLanguageServerParams> createTestDefault(SpringPropertyIndexProvider indexProvider, TypeUtilProvider typeUtilProvider) {
		return (SimpleLanguageServer server) -> {
			// Initialize project finders, project caches and project observers
			CompositeJavaProjectFinder javaProjectFinder = new CompositeJavaProjectFinder();
			MavenProjectCache mavenProjectCache = new MavenProjectCache(server, MavenCore.getDefault(), false, null, (uri, cpe) -> JavaDocProviders.createFor(cpe));
			mavenProjectCache.setAlwaysFireEventOnFileChanged(true);
			javaProjectFinder.addJavaProjectFinder(new MavenProjectFinder(mavenProjectCache));

			GradleProjectCache gradleProjectCache = new GradleProjectCache(server, GradleCore.getDefault(), false, null, (uri, cpe) -> JavaDocProviders.createFor(cpe));
			gradleProjectCache.setAlwaysFireEventOnFileChanged(true);
			javaProjectFinder.addJavaProjectFinder(new GradleProjectFinder(gradleProjectCache));

			CompositeProjectOvserver projectObserver = new CompositeProjectOvserver(Arrays.asList(mavenProjectCache, gradleProjectCache));

			return new BootLanguageServerParams(
					javaProjectFinder.filter(BootProjectUtil::isBootProject),
					projectObserver,
					indexProvider,
					(doc) -> SpringPropertyIndex.EMPTY_INDEX,
					typeUtilProvider,
					RunningAppProvider.NULL,
					SpringLiveHoverWatchdog.DEFAULT_INTERVAL
			);
		};
	}

	public static LSFactory<BootLanguageServerParams> createTestDefault() {
		return (SimpleLanguageServer server) -> {
			// Initialize project finders, project caches and project observers
			CompositeJavaProjectFinder javaProjectFinder = new CompositeJavaProjectFinder();
			MavenProjectCache mavenProjectCache = new MavenProjectCache(server, MavenCore.getDefault(), false, null, (uri, cpe) -> JavaDocProviders.createFor(cpe));
			mavenProjectCache.setAlwaysFireEventOnFileChanged(true);
			javaProjectFinder.addJavaProjectFinder(new MavenProjectFinder(mavenProjectCache));

			GradleProjectCache gradleProjectCache = new GradleProjectCache(server, GradleCore.getDefault(), false, null, (uri, cpe) -> JavaDocProviders.createFor(cpe));
			gradleProjectCache.setAlwaysFireEventOnFileChanged(true);
			javaProjectFinder.addJavaProjectFinder(new GradleProjectFinder(gradleProjectCache));

			CompositeProjectOvserver projectObserver = new CompositeProjectOvserver(Arrays.asList(mavenProjectCache, gradleProjectCache));

			DefaultSpringPropertyIndexProvider indexProvider = new DefaultSpringPropertyIndexProvider(javaProjectFinder, projectObserver);
			indexProvider.setProgressService(server.getProgressService());

			return new BootLanguageServerParams(
					javaProjectFinder.filter(BootProjectUtil::isBootProject),
					projectObserver,
					indexProvider,
					(doc) -> SpringPropertyIndex.EMPTY_INDEX,
					(IDocument doc) -> new TypeUtil(javaProjectFinder.find(new TextDocumentIdentifier(doc.getUri()))),
					RunningAppProvider.NULL,
					SpringLiveHoverWatchdog.DEFAULT_INTERVAL
			);
		};
	}
}
