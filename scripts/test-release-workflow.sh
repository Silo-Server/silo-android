#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"

ruby - "${repo_root}" <<'RUBY'
require "fileutils"
require "json"
require "open3"
require "psych"
require "strscan"
require "tmpdir"

repo_root = ARGV.fetch(0)
load_workflow = lambda do |name|
  Psych.safe_load(
    File.read(File.join(repo_root, ".github/workflows", name)), aliases: true
  ).fetch("jobs")
end
jobs = load_workflow.call("release.yml")
failures = []
check = lambda { |condition, message| failures << message unless condition }

# Evaluate the actual workflow condition using its supported expression subset.
# GitHub compares strings without case sensitivity and supplies success() when
# an if condition has no status function. Only literals/operators reach eval;
# reject any new expression syntax until this evaluator explicitly supports it.
job_runs = lambda do |job, needs, cancelled|
  implicit_success = Array(job.fetch("needs", [])).all? do |name|
    needs.dig(name, "result") == "success"
  end && !cancelled
  condition = job["if"]
  next implicit_success unless condition

  expression = condition.strip.sub(/\A\$\{\{\s*/, "").sub(/\s*\}\}\z/, "")
  scanner = StringScanner.new(expression)
  translated = []
  status_function = false
  until scanner.eos?
    next if scanner.skip(/\s+/)
    token = scanner.scan(
      /needs\.[\w-]+\.(?:result|outputs\.[\w-]+)|cancelled\(\)|'[^']*'|==|&&|\|\||[!()]/
    )
    raise "Unsupported job expression: #{scanner.rest}" unless token

    translated << case token
                  when "cancelled()"
                    status_function = true
                    cancelled.to_s
                  when /\Aneeds\./
                    needs.dig(*token.split(".").drop(1)).to_s.downcase.dump
                  when /\A'/
                    token[1...-1].downcase.dump
                  else
                    token
                  end
  end
  (status_function || implicit_success) && eval(translated.join(" "))
end

check.call(jobs.fetch("apks").fetch("needs").sort == %w[setup unit-tests].sort,
           "APK preparation must wait for setup/tests and overlap Play")
check.call(jobs.fetch("play-bundles").fetch("needs") == ["setup"],
           "Bundle preparation must overlap tests after setup")
check.call(jobs.fetch("play").fetch("needs").sort == %w[setup unit-tests play-bundles].sort,
           "Play must remain gated on setup/tests/bundles")
check.call(jobs.fetch("publish-release").fetch("needs").sort == %w[setup unit-tests play-bundles play apks].sort,
           "Publication must directly wait for setup/tests/Play/APKs")

statuses = %w[success failure cancelled skipped]
flags = ["true", "false", "", "unexpected"]
condition_cases = 0
statuses.repeated_permutation(5) do |setup, tests, bundles, play, apks|
  flags.product([false, true]).each do |flag, cancelled|
    needs = {
      "setup" => {"result" => setup, "outputs" => {"play_publish" => flag}},
      "unit-tests" => {"result" => tests},
      "play-bundles" => {"result" => bundles},
      "play" => {"result" => play},
      "apks" => {"result" => apks}
    }
    expected = !cancelled && [setup, tests, apks].all? { |result| result == "success" } &&
               ((flag == "true" && bundles == "success" && play == "success") ||
                (flag == "false" && bundles == "skipped" && play == "skipped"))
    actual = job_runs.call(jobs.fetch("publish-release"), needs, cancelled)
    check.call(actual == expected,
               "Publication gate mismatch: #{[setup, tests, bundles, play, apks, flag, cancelled].inspect}")
    condition_cases += 1
  end
end

statuses.repeated_permutation(3) do |setup, tests, bundles|
  flags.product([false, true]).each do |flag, cancelled|
    needs = {
      "setup" => {"result" => setup, "outputs" => {"play_publish" => flag}},
      "unit-tests" => {"result" => tests},
      "play-bundles" => {"result" => bundles}
    }
    prerequisites_pass = !cancelled && setup == "success" && tests == "success"
    check.call(job_runs.call(jobs.fetch("apks"), needs, cancelled) == prerequisites_pass,
               "APK preparation gate mismatch: #{[setup, tests, flag, cancelled].inspect}")
    check.call(job_runs.call(jobs.fetch("play-bundles"), needs, cancelled) == (!cancelled && setup == "success" && flag == "true"),
               "Bundle preparation gate mismatch: #{[setup, tests, bundles, flag, cancelled].inspect}")
    check.call(job_runs.call(jobs.fetch("play"), needs, cancelled) == (prerequisites_pass && bundles == "success" && flag == "true"),
               "Play gate mismatch: #{[setup, tests, flag, cancelled].inspect}")
    condition_cases += 3
  end
end

find_step = lambda do |job, name|
  job.fetch("steps").find { |step| step["name"] == name } || raise("Missing step: #{name}")
end
cache_name = "Cache Robolectric Android runtimes"
release_cache = find_step.call(jobs.fetch("unit-tests"), cache_name)
ci_cache = find_step.call(load_workflow.call("trusted-linux-ci.yml").fetch("unit-tests"), cache_name)
check.call(release_cache.values_at("uses", "with") == ci_cache.values_at("uses", "with"),
           "Release tests must share the normal CI Robolectric cache")
test_run = find_step.call(jobs.fetch("unit-tests"), "Run unit tests").fetch("run")
check.call(test_run.match?(/^\s+test\s*\\$/), "Release tests must keep the aggregate test task")

matrix = jobs.fetch("apks").fetch("strategy").fetch("matrix").fetch("include")
check.call(matrix.map { |entry| entry.fetch("module") }.sort == %w[androidApp androidTvApp],
           "APK preparation must include both phone and TV")
collect_step = find_step.call(jobs.fetch("apks"), "Collect release APKs")
publish_step = find_step.call(jobs.fetch("publish-release"), "Create or update release")
check.call(collect_step.fetch("env").fetch("SILO_VERSION_NAME") == "${{ needs.setup.outputs.version }}" &&
           publish_step.fetch("env").fetch("RELEASE_VERSION") == "${{ needs.setup.outputs.version }}",
           "Artifact names and aliases must use the same full version, including tag suffixes")

run_step = lambda do |step, root, env, entry = {}|
  script = step.fetch("run").gsub(/\$\{\{\s*matrix\.([\w-]+)\s*\}\}/) do
    entry.fetch(Regexp.last_match(1))
  end
  raise "Unresolved workflow expression in shell fixture" if script.include?("${{")
  Open3.capture3(env, "bash", "-c", script, chdir: root)
end

prepare_apks = lambda do |root, version|
  expected = {}
  matrix.each do |entry|
    apk_dir = File.join(root, File.dirname(entry.fetch("apk-path")))
    FileUtils.mkdir_p(apk_dir)
    %w[arm64-v8a armeabi-v7a x86_64 universal].each do |abi|
      bytes = "#{entry.fetch('module')} #{abi} APK fixture\0"
      File.binwrite(File.join(apk_dir, "#{entry.fetch('module')}-#{abi}-release.apk"), bytes)
      expected["#{entry.fetch('release-prefix')}-#{version}-#{abi}-release.apk"] = bytes
    end
    out, err, status = run_step.call(collect_step, root, {"SILO_VERSION_NAME" => version}, entry)
    check.call(status.success?, "APK collection failed: #{out}#{err}")
  end
  files = Dir.glob(File.join(root, "release-artifacts/**/*.apk"))
  check.call(files.map { |path| File.basename(path) }.sort == expected.keys.sort,
             "Preparation must transfer exactly eight unique versioned APKs")
  expected
end

mock_gh = lambda do |root, version, existing|
  bin_dir = File.join(root, "bin")
  FileUtils.mkdir_p(bin_dir)
  executable = File.join(bin_dir, "gh")
  File.write(executable, <<~MOCK)
    #!/usr/bin/env ruby
    require "json"
    File.open(ENV.fetch("GH_FIXTURE_CALLS"), "a") { |file| file.puts(ARGV.to_json) }
    if ARGV.first(2) == ["release", "view"]
      exit(ENV.fetch("GH_FIXTURE_EXISTING") == "true" ? 0 : 1)
    end
  MOCK
  FileUtils.chmod(0755, executable)
  {
    "PATH" => "#{bin_dir}:#{ENV.fetch('PATH')}",
    "GH_FIXTURE_CALLS" => File.join(root, "gh-calls.jsonl"),
    "GH_FIXTURE_EXISTING" => existing.to_s,
    "GH_REPO" => "fixture/silo-android",
    "RELEASE_TAG" => "v#{version}",
    "RELEASE_VERSION" => version,
    "GITHUB_SHA" => "0" * 40
  }
end

["1.2.3", "1.2.3-rc.1+2"].product([false, true]).each do |version, existing|
  Dir.mktmpdir("release-workflow-") do |root|
    expected = prepare_apks.call(root, version)
    %w[silo-android silo-android-tv].each do |prefix|
      bytes = expected.fetch("#{prefix}-#{version}-universal-release.apk")
      expected["#{prefix}-latest-universal-release.apk"] = bytes
      expected["#{prefix}-latest-universal-debug.apk"] = bytes
    end
    env = mock_gh.call(root, version, existing)
    out, err, status = run_step.call(publish_step, root, env)
    check.call(status.success?, "Publication fixture failed: #{out}#{err}")
    next unless status.success?
    calls = File.readlines(env.fetch("GH_FIXTURE_CALLS")).map { |line| JSON.parse(line) }
    mutation = existing ? "edit" : "create"
    check.call(calls.map { |call| call.first(2) } == [["release", "view"], ["release", mutation], ["release", "upload"]],
               "Publication must retain the create/update/upload sequence")
    upload = calls.last
    check.call(upload.first(3) == ["release", "upload", "v#{version}"] && upload.last == "--clobber",
               "Publication must retain its release tag and replacement behavior")
    upload_paths = upload[3...-1]
    check.call(upload_paths.map { |path| File.basename(path) }.sort == expected.keys.sort,
               "Publication must upload the twelve expected asset filenames")
    upload_paths.each do |path|
      check.call(File.binread(File.join(root, path)) == expected[File.basename(path)],
                 "Publication changed APK bytes: #{path}")
    end
  end
end

%w[silo-android silo-android-tv].product(%w[missing empty duplicate]).each do |prefix, defect|
  Dir.mktmpdir("release-workflow-invalid-") do |root|
    version = "1.2.3"
    prepare_apks.call(root, version)
    universal = Dir.glob(File.join(root, "release-artifacts/**/#{prefix}-#{version}-universal-release.apk")).fetch(0)
    case defect
    when "missing"
      FileUtils.rm(universal)
    when "empty"
      File.binwrite(universal, "")
    when "duplicate"
      duplicate_dir = File.join(root, "release-artifacts/duplicate")
      FileUtils.mkdir_p(duplicate_dir)
      FileUtils.cp(universal, duplicate_dir)
    end
    env = mock_gh.call(root, version, false)
    out, _err, status = run_step.call(publish_step, root, env)
    check.call(!status.success? && out.include?("Expected one nonempty universal APK for #{prefix}."),
               "Publication must reject a #{defect} universal APK for #{prefix}")
    check.call(!File.exist?(env.fetch("GH_FIXTURE_CALLS")),
               "Invalid APKs must fail before any release API call")
  end
end

unless failures.empty?
  warn failures.first(20).map { |message| "FAIL: #{message}" }.join("\n")
  warn "#{failures.length} release workflow self-test(s) failed"
  exit 1
end
out, err, status = Open3.capture3("ruby", File.join(repo_root, "scripts/test-play-bundle-lanes.rb"))
raise "Play bundle lane fixtures failed: #{out}#{err}" unless status.success?
puts out
puts "All release workflow self-tests passed (#{condition_cases} gate cases; APK filenames and bytes verified)"
RUBY
