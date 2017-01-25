/*******************************************************************************
 * Copyright (c) 2016 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.concourse;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ide.vscode.commons.util.Renderable;
import org.springframework.ide.vscode.commons.util.Renderables;
import org.springframework.ide.vscode.commons.util.ValueParsers;
import org.springframework.ide.vscode.commons.yaml.ast.NodeUtil;
import org.springframework.ide.vscode.commons.yaml.ast.YamlFileAST;
import org.springframework.ide.vscode.commons.yaml.path.YamlPath;
import org.springframework.ide.vscode.commons.yaml.path.YamlPathSegment;
import org.springframework.ide.vscode.commons.yaml.schema.DynamicSchemaContext;
import org.springframework.ide.vscode.commons.yaml.schema.YType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.AbstractType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.YAtomicType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.YBeanType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.YBeanUnionType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeFactory.YTypedPropertyImpl;

import reactor.core.publisher.Flux;

import org.springframework.ide.vscode.commons.yaml.schema.YTypeUtil;
import org.springframework.ide.vscode.commons.yaml.schema.YValueHint;
import org.springframework.ide.vscode.commons.yaml.schema.YamlSchema;

/**
 * @author Kris De Volder
 */
public class PipelineYmlSchema implements YamlSchema {

	//TODO: the infos for composing this should probably be integrated somehow in the ResourceTypeRegistry so
	// we only have a list of built-in resource types in a single place.
	public static final YValueHint[] BUILT_IN_RESOURCE_TYPES = {
			hint("git", "The 'git' resource can pull and push to git repositories."),
			hint("hg", "The 'hg' resource can pull and push to Mercurial repositories."),
			hint("time",  "The 'time' resource can start jobs on a schedule or timestamp outputs."),
			hint("s3", "The 's3' resource can fetch from and upload to S3 buckets."),
			hint("archive", "The 'archive' resource can fetch and extract .tar.gz archives."),
			hint("semver", "The 'semver' resource can set or bump version numbers."),
			hint("github-release", "The 'github-release' resource can fetch and publish versioned GitHub resources."),
			hint("docker-image", "The 'docker-image' resource can fetch, build, and push Docker images."),
			hint("tracker", "The 'tracker' resource can deliver stories and bugs on Pivotal Tracker."),
			hint("pool", "The 'pool' resource allows you to configure how to serialize use of an external system. "
					+ "This lets you prevent test interference or overwork on shared systems."),
			hint("cf", "The cf resource can deploy an application to Cloud Foundry."),
			hint("bosh-io-release", "The bosh-io-release resource can track and fetch new BOSH releases from bosh.io."),
			hint("bosh-io-stemcell", "The bosh-io-stemcell resource can track and fetch new BOSH stemcells from bosh.io."),
			hint("bosh-deployment", "The bosh-deployment resource can deploy BOSH stemcells and releases."),
			hint("vagrant-cloud", "The vagrant-cloud resource can fetch and publish Vagrant boxes to Atlas.")
	};

	public static final Set<String> BUILT_IN_RESOURCE_TYPE_NAMES = Flux.fromArray(PipelineYmlSchema.BUILT_IN_RESOURCE_TYPES)
			.map(YValueHint::getValue)
			.collect(Collectors.toSet())
			.block();


	private final YBeanType TOPLEVEL_TYPE;
	private final YTypeUtil TYPE_UTIL;

	public final YTypeFactory f = new YTypeFactory();
	public final YType t_string = f.yatomic("String");
	public final YType t_ne_string = f.yatomic("String")
			.parseWith(ValueParsers.NE_STRING);

	public final YType t_strings = f.yseq(t_string);
	public final YType t_pair = f.ybean("NameValuePair",
			f.yprop("name", t_string),
			f.yprop("value", t_string)
	);
	public final YType t_pair_list = f.yseq(t_pair);

	public final YAtomicType t_boolean = f.yenum("boolean", "true", "false");
	public final YType t_any = f.yany("Object");
	public final YType t_params = f.ymap(t_string, t_any);
	public final YType t_string_params = f.ymap(t_string, t_string);
	public final YType t_pos_integer = f.yatomic("Positive Integer")
			.parseWith(ValueParsers.POS_INTEGER);
	public final YType t_strictly_pos_integer = f.yatomic("Strictly Positive Integer")
			.parseWith(ValueParsers.integerAtLeast(1));

	public final YAtomicType t_resource_name;
	public final YAtomicType t_job_name;
	public final YAtomicType t_resource_type_name;

	private final ResourceTypeRegistry resourceTypes = new ResourceTypeRegistry();

	public PipelineYmlSchema(ConcourseModel models) {
		TYPE_UTIL = f.TYPE_UTIL;

		// define schema types
		TOPLEVEL_TYPE = f.ybean("Pipeline");

		YAtomicType t_duration = f.yatomic("Duration");
		t_duration.parseWith(ConcourseValueParsers.DURATION);

		YAtomicType t_version = f.yatomic("Version");
		t_version.addHints("latest", "every");

		YAtomicType t_image_type = f.yatomic("ImageType");
		t_image_type.addHints("docker_image");

		t_resource_type_name = f.yenumFromHints("ResourceType Name",
				(parseString, validValues) ->  {
					return "The '"+parseString+"' Resource Type does not exist. Existing types: "+validValues;
				},
				(DynamicSchemaContext dc) -> {
					return models.getResourceTypeNameHints(dc.getDocument());
				}
		);

		t_resource_name = f.yenum("Resource Name",
				(parseString, validValues) ->  {
					return "The '"+parseString+"' resource does not exist. Existing resources: "+validValues;
				},
				(DynamicSchemaContext dc) -> {
					return (models.getResourceNames(dc.getDocument()));
				}
		);

		t_job_name = f.yenum("Job Name",
				(parseString, validValues) ->  {
					return "The '"+parseString+"' Job does not exist. Existing jobs: "+validValues;
				},
				(DynamicSchemaContext dc) -> {
					return models.getJobNames(dc.getDocument());
				}
		);

		YAtomicType resourceNameDef = f.yatomic("Resource Name");
		resourceNameDef.parseWith(ConcourseValueParsers.resourceNameDef(models));
		YAtomicType jobNameDef = f.yatomic("Job Name");
		jobNameDef.parseWith(ConcourseValueParsers.jobNameDef(models));
		YAtomicType resourceTypeNameDef = f.yatomic("ResourceType Name");
		resourceTypeNameDef.parseWith(ConcourseValueParsers.resourceTypeNameDef(models));

		YBeanType getStep = f.ybean("GetStep");
		addProp(getStep, "get", t_resource_name);
		addProp(getStep, "resource", t_string);
		addProp(getStep, "version", t_version);
		addProp(getStep, "passed", f.yseq(t_job_name));
		addProp(getStep, "params", f.contextAware("GetParams", (dc) ->
			resourceTypes.getInParamsType(getResourceType("get", models, dc))
		));
		addProp(getStep, "trigger", t_boolean);

		YBeanType putStep = f.ybean("PutStep");
		addProp(putStep, "put", t_resource_name);
		addProp(putStep, "resource", t_job_name);
		addProp(putStep, "params", f.contextAware("PutParams", (dc) ->
			resourceTypes.getOutParamsType(getResourceType("put", models, dc))
		));
		addProp(putStep, "get_params", f.contextAware("GetParams", (dc) ->
			resourceTypes.getInParamsType(getResourceType("put", models, dc))
		));
		YBeanType taskStep = f.ybean("TaskStep");
		addProp(taskStep, "task", t_ne_string);
		addProp(taskStep, "file", t_string);
		addProp(taskStep, "config", t_any);
		addProp(taskStep, "privileged", t_boolean);
		addProp(taskStep, "params", t_params);
		addProp(taskStep, "image", t_ne_string);
		addProp(taskStep, "input_mapping",  f.ymap(t_ne_string, t_resource_name));
		addProp(taskStep, "output_mapping", t_string_params);

		YBeanType aggregateStep = f.ybean("AggregateStep");
		YBeanType doStep = f.ybean("DoStep");
		YBeanType tryStep = f.ybean("TryStep");

		YBeanType[] stepTypes = {
				getStep,
				putStep,
				taskStep,
				aggregateStep,
				doStep,
				tryStep
		};
		YBeanUnionType step = f.yunion("Step", stepTypes);
		addProp(aggregateStep, "aggregate", f.yseq(step));
		addProp(doStep, "do", f.yseq(step));
		addProp(tryStep, "try", step);

		// shared properties applicable for any subtype of Step:
		for (YBeanType subStep : stepTypes) {
			addProp(step, subStep, "on_success", step);
			addProp(step, subStep, "on_failure", step);
			addProp(step, subStep, "ensure", step);
			addProp(step, subStep, "attempts", t_strictly_pos_integer);
			addProp(step, subStep, "tags", t_strings);
			addProp(step, subStep, "timeout", t_duration);
		}

		YType resourceSource = f.contextAware("ResourceSource", (dc) ->
			resourceTypes.getSourceType(getResourceTypeTag(models, dc))
		);

		YBeanType resource = f.ybean("Resource");
		addProp(resource, "name", resourceNameDef).isRequired(true);
		addProp(resource, "type", t_resource_type_name).isRequired(true);
		addProp(resource, "source", resourceSource);
		addProp(resource, "check_every", t_duration);

		YBeanType job = f.ybean("Job");
		addProp(job, "name", jobNameDef).isRequired(true);
		addProp(job, "plan", f.yseq(step)).isRequired(true);
		addProp(job, "serial", t_boolean);
		addProp(job, "build_logs_to_retain", t_pos_integer);
		addProp(job, "serial_groups", t_strings);
		addProp(job, "max_in_flight", t_pos_integer);
		addProp(job, "public", t_boolean);
		addProp(job, "disable_manual_trigger", t_boolean);

		YBeanType resourceType = f.ybean("ResourceType");
		addProp(resourceType, "name", resourceTypeNameDef).isRequired(true);
		addProp(resourceType, "type", t_image_type).isRequired(true);
		addProp(resourceType, "source", resourceSource);

		YBeanType group = f.ybean("Group");
		addProp(group, "name", t_ne_string).isRequired(true);
		addProp(group, "resources", f.yseq(t_resource_name));
		addProp(group, "jobs", f.yseq(t_job_name));

		addProp(TOPLEVEL_TYPE, "resources", f.yseq(resource));
		addProp(TOPLEVEL_TYPE, "jobs", f.yseq(job));
		addProp(TOPLEVEL_TYPE, "resource_types", f.yseq(resourceType));
		addProp(TOPLEVEL_TYPE, "groups", f.yseq(group));

		initializeDefaultResourceTypes();
	}

	private static YValueHint hint(String value, String description) {
		return YTypeFactory.hint(value, value + " - " + description);
	}

	private void initializeDefaultResourceTypes() {
		// git :
		{
			YBeanType source = f.ybean("GitSource");
			addProp(source, "uri", t_string).isRequired(true);
			addProp(source, "branch", t_string).isRequired(true);
			addProp(source, "private_key", t_string);
			addProp(source, "username", t_string);
			addProp(source, "password", t_string);
			addProp(source, "paths", t_strings);
			addProp(source, "ignore_paths", t_strings);
			addProp(source, "skip_ssl_verification", t_boolean);
			addProp(source, "tag_filter", t_string);
			addProp(source, "git_config", t_pair_list);
			addProp(source, "disable_ci_skip", t_boolean);
			addProp(source, "commit_verification_keys", t_strings);
			addProp(source, "commit_verification_key_ids", t_strings);
			addProp(source, "gpg_keyserver", t_string);

			YBeanType get = f.ybean("GitGetParams");
			addProp(get, "depth", t_pos_integer);
			addProp(get, "submodules", f.yany("GitSubmodules").addHints("all", "none"));
			addProp(get, "disable_git_lfs", t_boolean);

			YBeanType put = f.ybean("GitPutParams");
			addProp(put, "repository", t_ne_string).isRequired(true);
			addProp(put, "rebase", t_boolean);
			addProp(put, "tag", t_ne_string);
			addProp(put, "only_tag", t_boolean);
			addProp(put, "tag_prefix", t_string);
			addProp(put, "force", t_boolean);
			addProp(put, "annotate", t_ne_string);

			resourceTypes.def("git", source, get, put);
		}
		//docker-image:
		{
			YBeanType source = f.ybean("DockerImageSource");
			addProp(source, "repository", t_ne_string).isRequired(true);
			addProp(source, "tag", t_ne_string);
			addProp(source, "username", t_ne_string);
			addProp(source, "password", t_ne_string);
			addProp(source, "aws_access_key_id", t_ne_string);
			addProp(source, "aws_secret_access_key", t_ne_string);
			addProp(source, "insecure_registries", t_strings);
			addProp(source, "registry_mirror", t_ne_string);
			addProp(source, "ca_certs", f.yseq(f.ybean("CaCertsEntry",
					f.yprop("domain", t_ne_string),
					f.yprop("cert", t_ne_string)
			)));
			addProp(source, "client_certs", f.yseq(f.ybean("ClientCertsEntry",
					f.yprop("domain", t_ne_string),
					f.yprop("key", t_ne_string),
					f.yprop("cert", t_ne_string)
			)));

			YBeanType get = f.ybean("DockerImageGetParams");
			addProp(get, "save", t_boolean);
			addProp(get, "rootfs", t_boolean);
			addProp(get, "skip_download", t_boolean);

			YBeanType put = f.ybean("DockerImagePutParams");
			addProp(put, "build", t_ne_string);
			addProp(put, "load", t_ne_string);
			addProp(put, "dockerfile", t_ne_string);
			addProp(put, "cache", t_boolean);
			addProp(put, "cache_tag", t_ne_string);
			addProp(put, "load_base", t_ne_string);
			addProp(put, "load_file", t_ne_string);
			addProp(put, "load_repository", t_ne_string);
			addProp(put, "load_tag", t_ne_string);
			addProp(put, "import_file", t_ne_string);
			addProp(put, "pull_repository", t_ne_string).isDeprecated(true);
			addProp(put, "pull_tag", t_ne_string).isDeprecated(true);
			addProp(put, "tag", t_ne_string);
			addProp(put, "tag_prefix", t_ne_string);
			addProp(put, "tag_as_latest", t_boolean);
			addProp(put, "build_args", t_string_params);
			addProp(put, "build_args_file", t_ne_string);

			resourceTypes.def("docker-image", source, get, put);
		}
	}

	private String getResourceType(String resourceNameProp, ConcourseModel models, DynamicSchemaContext dc) {
		String resourceName = getParentPropertyValue(resourceNameProp, models, dc);
		if (resourceName!=null) {
			return models.getResourceType(dc.getDocument(), resourceName);
		}
		return null;
	}

	private String getResourceTypeTag(ConcourseModel models, DynamicSchemaContext dc) {
		return getParentPropertyValue("type", models, dc);
	}

	private String getParentPropertyValue(String propName, ConcourseModel models, DynamicSchemaContext dc) {
		YamlPath path = dc.getPath();
		if (path!=null) {
			YamlFileAST root = models.getSafeAst(dc.getDocument());
			if (root!=null) {
				return NodeUtil.asScalar(path.dropLast().append(YamlPathSegment.valueAt(propName)).traverseToNode(root));
			}
		}
		return null;
	}

	private YTypedPropertyImpl prop(AbstractType beanType, String name, YType type) {
		YTypedPropertyImpl prop = f.yprop(name, type);
		prop.setDescriptionProvider(descriptionFor(beanType, name));
		return prop;
	}

	private YTypedPropertyImpl addProp(AbstractType superType, AbstractType bean, String name, YType type) {
		YTypedPropertyImpl p = prop(superType, name, type);
		bean.addProperty(p);
		return p;
	}

	private YTypedPropertyImpl addProp(AbstractType bean, String name, YType type) {
		return addProp(bean, bean, name, type);
	}

	public static Renderable descriptionFor(YType owner, String propName) {
		String typeName = owner.toString();
		return Renderables.fromClasspath(PipelineYmlSchema.class, "/desc/"+typeName+"/"+propName);
	}

	@Override
	public YBeanType getTopLevelType() {
		return TOPLEVEL_TYPE;
	}

	@Override
	public YTypeUtil getTypeUtil() {
		return TYPE_UTIL;
	}
}
