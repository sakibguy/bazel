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
// limitations under the License

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Module}. */
@RunWith(JUnit4.class)
public class ModuleTest {

  @Test
  public void canonicalizedTargetPatterns_good() throws Exception {
    ModuleKey key = createModuleKey("self", "1.0");
    Module module =
        Module.builder()
            .setExecutionPlatformsToRegister(ImmutableList.of("//:self_target"))
            .setToolchainsToRegister(ImmutableList.of("@root//:root_target", "@hi//:hi_target"))
            .addDep("hi", createModuleKey("hello", "2.0"))
            .addDep("root", ModuleKey.ROOT)
            .build();
    assertThat(module.getCanonicalizedExecutionPlatformsToRegister(key))
        .containsExactly("@self.1.0//:self_target")
        .inOrder();
    assertThat(module.getCanonicalizedToolchainsToRegister(key))
        .containsExactly("@//:root_target", "@hello.2.0//:hi_target")
        .inOrder();
  }

  @Test
  public void canonicalizedTargetPatterns_bad() throws Exception {
    ModuleKey key = createModuleKey("self", "1.0");
    Module module =
        Module.builder()
            .setExecutionPlatformsToRegister(ImmutableList.of("@what//:target"))
            .setToolchainsToRegister(ImmutableList.of("@hi:target"))
            .addDep("hi", createModuleKey("hello", "2.0"))
            .addDep("root", ModuleKey.ROOT)
            .build();
    assertThrows(
        ExternalDepsException.class,
        () -> module.getCanonicalizedExecutionPlatformsToRegister(key));
    assertThrows(
        ExternalDepsException.class, () -> module.getCanonicalizedToolchainsToRegister(key));
  }
}
