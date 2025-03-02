// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth8.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static org.junit.Assert.fail;

import com.google.auto.value.AutoValue;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BzlmodRepoRuleHelperImpl}. */
@RunWith(JUnit4.class)
public final class BzlmodRepoRuleHelperTest extends FoundationTestCase {

  private Path workspaceRoot;
  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private EvaluationContext evaluationContext;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setup() throws Exception {
    workspaceRoot = scratch.dir("/ws");
    differencer = new SequencedRecordingDifferencer();
    evaluationContext =
        EvaluationContext.newBuilder().setNumThreads(8).setEventHandler(reporter).build();
    registryFactory = new FakeRegistry.Factory();
    AtomicReference<PathPackageLocator> packageLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                outputBase,
                ImmutableList.of(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootDirectory, outputBase, rootDirectory),
            rootDirectory,
            /* defaultSystemJavabase= */ null,
            AnalysisMock.get().getProductName());
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            packageLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);

    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(FileValue.FILE, new FileFunction(packageLocator, directories))
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(registryFactory, workspaceRoot, ImmutableMap.of()))
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(
                    GET_REPO_SPEC_BY_NAME_FUNCTION,
                    new GetRepoSpecByNameFunction(new BzlmodRepoRuleHelperImpl()))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .buildOrThrow(),
            differencer);

    PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT);
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.WARNING);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
  }

  @Test
  public void getRepoSpec_bazelModule() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
            .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<GetRepoSpecByNameValue> result =
        evaluator.evaluate(ImmutableList.of(getRepoSpecByNameKey("ccc~2.0")), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }

    Optional<RepoSpec> repoSpec = result.get(getRepoSpecByNameKey("ccc~2.0")).rule();
    assertThat(repoSpec)
        .hasValue(
            RepoSpec.builder()
                .setRuleClassName("local_repository")
                .setAttributes(
                    ImmutableMap.of("name", "ccc~2.0", "path", "/usr/local/modules/ccc~2.0"))
                .build());
  }

  @Test
  public void getRepoSpec_nonRegistryOverride() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "local_path_override(module_name='ccc',path='/foo/bar/C')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
            .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<GetRepoSpecByNameValue> result =
        evaluator.evaluate(
            ImmutableList.of(getRepoSpecByNameKey("ccc~override")), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }

    Optional<RepoSpec> repoSpec = result.get(getRepoSpecByNameKey("ccc~override")).rule();
    assertThat(repoSpec)
        .hasValue(
            RepoSpec.builder()
                .setRuleClassName("local_repository")
                .setAttributes(
                    ImmutableMap.of(
                        "name", "ccc~override",
                        "path", "/foo/bar/C"))
                .build());
  }

  @Test
  public void getRepoSpec_singleVersionOverride() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "single_version_override(",
        "  module_name='ccc',version='3.0',patches=['//:foo.patch'], patch_cmds=['echo hi'],"
            + " patch_strip=1)");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
            .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
            .addModule(createModuleKey("ccc", "3.0"), "module(name='ccc', version='3.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<GetRepoSpecByNameValue> result =
        evaluator.evaluate(ImmutableList.of(getRepoSpecByNameKey("ccc~3.0")), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }

    Optional<RepoSpec> repoSpec = result.get(getRepoSpecByNameKey("ccc~3.0")).rule();
    assertThat(repoSpec)
        .hasValue(
            RepoSpec.builder()
                // This obviously wouldn't work in the real world since local_repository doesn't
                // support patches -- but in the real world, registries also don't use
                // local_repository.
                .setRuleClassName("local_repository")
                .setAttributes(
                    ImmutableMap.of(
                        "name",
                        "ccc~3.0",
                        "path",
                        "/usr/local/modules/ccc~3.0",
                        "patches",
                        ImmutableList.of("//:foo.patch"),
                        "patch_cmds",
                        ImmutableList.of("echo hi"),
                        "patch_args",
                        ImmutableList.of("-p1")))
                .build());
  }

  @Test
  public void getRepoSpec_multipleVersionOverride() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "multiple_version_override(module_name='ddd',versions=['1.0','2.0'])");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb', version='1.0');bazel_dep(name='ddd',version='1.0')")
            .addModule(
                createModuleKey("ccc", "2.0"),
                "module(name='ccc', version='2.0');bazel_dep(name='ddd',version='2.0')")
            .addModule(createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
            .addModule(createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<GetRepoSpecByNameValue> result =
        evaluator.evaluate(ImmutableList.of(getRepoSpecByNameKey("ddd~2.0")), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }

    Optional<RepoSpec> repoSpec = result.get(getRepoSpecByNameKey("ddd~2.0")).rule();
    assertThat(repoSpec)
        .hasValue(
            RepoSpec.builder()
                .setRuleClassName("local_repository")
                .setAttributes(
                    ImmutableMap.of("name", "ddd~2.0", "path", "/usr/local/modules/ddd~2.0"))
                .build());
  }

  @Test
  public void getRepoSpec_notFound() throws Exception {
    scratch.file(
        workspaceRoot.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry("/usr/local/modules")
            .addModule(createModuleKey("bbb", "1.0"), "module(name='bbb', version='1.0')");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));

    EvaluationResult<GetRepoSpecByNameValue> result =
        evaluator.evaluate(ImmutableList.of(getRepoSpecByNameKey("C")), evaluationContext);
    if (result.hasError()) {
      fail(result.getError().toString());
    }

    Optional<RepoSpec> repoSpec = result.get(getRepoSpecByNameKey("C")).rule();
    assertThat(repoSpec).isEmpty();
  }

  /** A helper SkyFunction to invoke BzlmodRepoRuleHelper */
  private static final SkyFunctionName GET_REPO_SPEC_BY_NAME_FUNCTION =
      SkyFunctionName.createHermetic("GET_REPO_SPEC_BY_NAME_FUNCTION");

  @AutoValue
  abstract static class GetRepoSpecByNameValue implements SkyValue {
    abstract Optional<RepoSpec> rule();

    static GetRepoSpecByNameValue create(Optional<RepoSpec> rule) {
      return new AutoValue_BzlmodRepoRuleHelperTest_GetRepoSpecByNameValue(rule);
    }
  }

  private static final class GetRepoSpecByNameFunction implements SkyFunction {
    private final BzlmodRepoRuleHelper bzlmodRepoRuleHelper;

    GetRepoSpecByNameFunction(BzlmodRepoRuleHelper bzlmodRepoRuleHelper) {
      this.bzlmodRepoRuleHelper = bzlmodRepoRuleHelper;
    }

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      RepositoryName repositoryName = (RepositoryName) skyKey.argument();
      Optional<RepoSpec> result;
      try {
        result = bzlmodRepoRuleHelper.getRepoSpec(env, repositoryName);
        if (env.valuesMissing()) {
          return null;
        }
      } catch (IOException e) {
        throw new GetRepoSpecByNameFunctionException(e, Transience.PERSISTENT);
      }
      return GetRepoSpecByNameValue.create(result);
    }
  }

  private static final class Key extends AbstractSkyKey<RepositoryName> {
    private Key(RepositoryName arg) {
      super(arg);
    }

    @Override
    public SkyFunctionName functionName() {
      return GET_REPO_SPEC_BY_NAME_FUNCTION;
    }
  }

  private static final class GetRepoSpecByNameFunctionException extends SkyFunctionException {
    GetRepoSpecByNameFunctionException(IOException e, Transience transience) {
      super(e, transience);
    }
  }

  private static SkyKey getRepoSpecByNameKey(String repositoryName) {
    return new Key(RepositoryName.createUnvalidated(repositoryName));
  }
}
