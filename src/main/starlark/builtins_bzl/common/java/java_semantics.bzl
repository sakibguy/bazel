# Copyright 2021 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Java Semantics
"""

java_common = _builtins.toplevel.java_common
JavaPluginInfo = _builtins.toplevel.JavaPluginInfo

def _macro_preprocess(kwargs):
    pass

def _check_rule(ctx):
    pass

def _check_dependency_rule_kinds(ctx):
    pass

def _preprocess(ctx):
    return []

def _postprocess(ctx, base_info):
    return base_info.java_info

semantics = struct(
    EXPERIMENTAL_USE_FILEGROUPS_IN_JAVALIBRARY = False,
    EXPERIMENTAL_USE_OUTPUTATTR_IN_JAVALIBRARY = True,
    COLLECT_SRCS_FROM_PROTO_LIBRARY = False,
    EXTRA_SRCS_TYPES = [],
    EXTRA_ATTRIBUTES = {
        "_java_toolchain": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_toolchain",
            providers = [java_common.JavaToolchainInfo],
        ),
        "_java_plugins": attr.label(
            default = "@bazel_tools//tools/jdk:java_plugins_flag_alias",
            providers = [JavaPluginInfo],
        ),
        "_proguard_allowlister": attr.label(
            allow_files = True,
            default = "@bazel_tools//tools/jdk:proguard_whitelister",
            cfg = "exec",
            executable = True,
        ),
    },
    EXTRA_DEPS = [],
    ALLOWED_RULES_IN_DEPS = [
        "cc_binary",  # NB: linkshared=1
        "cc_library",
        "genrule",
        "genproto",  # TODO(bazel-team): we should filter using providers instead (starlark rule).
        "java_import",
        "java_library",
        "java_proto_library",
        "java_lite_proto_library",
        "proto_library",
        "sh_binary",
        "sh_library",
    ],
    ALLOWED_RULES_IN_DEPS_WITH_WARNING = [],
    macro_preprocess = _macro_preprocess,
    check_rule = _check_rule,
    check_dependency_rule_kinds = _check_dependency_rule_kinds,
    preprocess = _preprocess,
    postprocess = _postprocess,
)
