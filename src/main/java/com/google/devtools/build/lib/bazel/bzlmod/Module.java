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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/**
 * Represents a node in the external dependency graph.
 *
 * <p>In particular, it represents a specific version of a module; there can be multiple {@link
 * Module}s in a dependency graph with the same name but with different versions (such as after
 * discovery but before selection, or when there's a multiple_version_override in play).
 */
@AutoValue
public abstract class Module {

  /** The name of the module. Can be empty if this is the root module. */
  public abstract String getName();

  /** The version of the module. Must be empty iff the module has a {@link NonRegistryOverride}. */
  public abstract Version getVersion();

  /**
   * The compatibility level of the module, which essentially signifies the "major version" of the
   * module in terms of SemVer.
   */
  public abstract int getCompatibilityLevel();

  /**
   * Target patterns identifying execution platforms to register when this module is selected. Note
   * that these are what was written in module files verbatim, and don't contain canonical repo
   * names.
   */
  public abstract ImmutableList<String> getExecutionPlatformsToRegister();

  /**
   * Target patterns identifying toolchains to register when this module is selected. Note that
   * these are what was written in module files verbatim, and don't contain canonical repo names.
   */
  public abstract ImmutableList<String> getToolchainsToRegister();

  /**
   * Target patterns (with canonical repo names) identifying execution platforms to register when
   * this module is selected. We need the key of this module in the dep graph to know its canonical
   * repo name.
   */
  public final ImmutableList<String> getCanonicalizedExecutionPlatformsToRegister(ModuleKey key)
      throws ExternalDepsException {
    return canonicalizeTargetPatterns(getExecutionPlatformsToRegister(), key);
  }

  /**
   * Target patterns (with canonical repo names) identifying toolchains to register when this module
   * is selected. We need the key of this module in the dep graph to know its canonical repo name.
   */
  public final ImmutableList<String> getCanonicalizedToolchainsToRegister(ModuleKey key)
      throws ExternalDepsException {
    return canonicalizeTargetPatterns(getToolchainsToRegister(), key);
  }

  /**
   * Rewrites the given target patterns to have canonical repo names, assuming that they're
   * originally written in the context of the module identified by {@code key} and {@code module}.
   */
  private ImmutableList<String> canonicalizeTargetPatterns(
      ImmutableList<String> targetPatterns, ModuleKey key) throws ExternalDepsException {
    ImmutableList.Builder<String> renamedPatterns = ImmutableList.builder();
    for (String pattern : targetPatterns) {
      if (!pattern.startsWith("@")) {
        renamedPatterns.add("@" + key.getCanonicalRepoName() + pattern);
        continue;
      }
      int doubleSlashIndex = pattern.indexOf("//");
      if (doubleSlashIndex == -1) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE, "%s refers to malformed target pattern: %s", key, pattern);
      }
      String repoName = pattern.substring(1, doubleSlashIndex);
      ModuleKey depKey = getDeps().get(repoName);
      if (depKey == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "%s refers to target pattern %s with unknown repo %s",
            key,
            pattern,
            repoName);
      }
      renamedPatterns.add(
          "@" + depKey.getCanonicalRepoName() + pattern.substring(doubleSlashIndex));
    }
    return renamedPatterns.build();
  }

  /**
   * The direct dependencies of this module. The key type is the repo name of the dep, and the value
   * type is the ModuleKey (name+version) of the dep.
   */
  public abstract ImmutableMap<String, ModuleKey> getDeps();

  /**
   * The registry where this module came from. Must be null iff the module has a {@link
   * NonRegistryOverride}.
   */
  @Nullable
  public abstract Registry getRegistry();

  /** Returns a {@link Builder} that starts out with the same fields as this object. */
  public abstract Builder toBuilder();

  /** Returns a new, empty {@link Builder}. */
  public static Builder builder() {
    return new AutoValue_Module.Builder()
        .setName("")
        .setVersion(Version.EMPTY)
        .setCompatibilityLevel(0)
        .setExecutionPlatformsToRegister(ImmutableList.of())
        .setToolchainsToRegister(ImmutableList.of());
  }

  /**
   * Returns a new {@link Module} with all values in {@link #getDeps} transformed using the given
   * function.
   */
  public Module withDepKeysTransformed(UnaryOperator<ModuleKey> transform) {
    ImmutableMap.Builder<String, ModuleKey> newDeps = new ImmutableMap.Builder<>();
    for (Map.Entry<String, ModuleKey> entry : getDeps().entrySet()) {
      newDeps.put(entry.getKey(), transform.apply(entry.getValue()));
    }
    return toBuilder().setDeps(newDeps.build()).build();
  }

  /** Builder type for {@link Module}. */
  @AutoValue.Builder
  public abstract static class Builder {
    /** Optional; defaults to the empty string. */
    public abstract Builder setName(String value);

    /** Optional; defaults to {@link Version#EMPTY}. */
    public abstract Builder setVersion(Version value);

    /** Optional; defaults to {@code 0}. */
    public abstract Builder setCompatibilityLevel(int value);

    /** Optional; defaults to an empty list. */
    public abstract Builder setExecutionPlatformsToRegister(ImmutableList<String> value);

    /** Optional; defaults to an empty list. */
    public abstract Builder setToolchainsToRegister(ImmutableList<String> value);

    public abstract Builder setDeps(ImmutableMap<String, ModuleKey> value);

    public abstract Builder setRegistry(Registry value);

    abstract ImmutableMap.Builder<String, ModuleKey> depsBuilder();

    public Builder addDep(String depRepoName, ModuleKey depKey) {
      depsBuilder().put(depRepoName, depKey);
      return this;
    }

    public abstract Module build();
  }
}
