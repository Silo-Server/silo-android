#!/usr/bin/env ruby

require "base64"
require "fileutils"
require "json"
require "open3"
require "rbconfig"
require "tmpdir"

# Execute the real Fastfile and manifest module with temporary source and bytes.
# The only action implementations below are inert; unexpected actions fail.
class FixtureFailure < StandardError; end
class LaneRejected < StandardError; end
class SyntheticBuildFailure < StandardError; end
class SyntheticKeyFailure < StandardError; end
class SyntheticChangelogFailure < StandardError; end

module FixtureUI
  class << self
    attr_accessor :messages

    def user_error!(message)
      raise LaneRejected, message
    end

    %i[message important success].each do |name|
      define_method(name) { |message| messages << [name, message] }
    end
  end
end

class LaneFixture
  UI = FixtureUI
  PHONE = "androidApp/build/outputs/bundle/release/androidApp-release.aab"
  TV = "androidTvApp/build/outputs/bundle/release/androidTvApp-release.aab"
  KEY_DATA = '{"fixture":"publisher"}'

  class << self
    attr_reader :lanes

    def default_platform(name)
      raise FixtureFailure, "Unexpected default platform" unless name == :android
    end

    def platform(name)
      raise FixtureFailure, "Unexpected platform" unless name == :android
      yield
    end

    def desc(_description); end

    def lane(name, &block)
      @lanes ||= {}
      raise FixtureFailure, "Duplicate lane" if @lanes.key?(name)
      @lanes[name] = block
    end
  end

  attr_reader :root, :events, :gradle_calls, :uploads, :messages_before_gradle
  attr_accessor :fail_build, :fail_key, :fail_changelog, :produce

  def initialize(root)
    @root = root
    @events, @gradle_calls, @uploads = [], [], []
    @produce = [PHONE, TV]
  end

  def invoke(name)
    instance_exec(&self.class.lanes.fetch(name))
  end

  def gradle(**options)
    events << :gradle
    gradle_calls << options
    @messages_before_gradle = FixtureUI.messages.map(&:dup)
    raise SyntheticBuildFailure, "Deliberate fixture build failure" if fail_build
    produce.each do |relative|
      path = File.join(root, relative)
      FileUtils.mkdir_p(File.dirname(path))
      File.binwrite(path, relative == PHONE ? "phone AAB fixture\0" : "TV AAB fixture\0")
    end
  end

  def upload_to_play_store(**options)
    events << :upload
    uploads << options
  end

  def sh(command, log:)
    unless command == "git log -1 --pretty=format:%s" && log == false
      raise FixtureFailure, "Unexpected shell action"
    end
    events << :changelog_fallback
    "Fixture commit subject\n"
  end

  def method_missing(name, *_arguments, **_keywords, &_block)
    raise FixtureFailure, "Unexpected Fastlane action: #{name}"
  end

  def respond_to_missing?(_name, _private = false)
    false
  end
end

module FixtureHelpers
  def play_json_key_data
    events << :publisher_key
    raise SyntheticKeyFailure, "Deliberate fixture key failure" if fail_key
    LaneFixture::KEY_DATA
  end

  def require_signing_env!
    events << :signing_env
    super
  end

  def resolve_release_keystore
    events << :keystore
    # Never permit the real helper's local credential fallback in this harness.
    path = ENV["SILO_RELEASE_KEYSTORE"].to_s
    unless ENV["SILO_RELEASE_KEYSTORE_B64"].to_s.empty? && path == File.join(root, "fixture.jks")
      raise FixtureFailure, "Unexpected keystore lookup"
    end
    super
  end

  def materialize_google_services_json
    events << :fcm
    super
  end

  def write_play_changelog
    events << :changelog
    raise SyntheticChangelogFailure, "Deliberate fixture changelog failure" if fail_changelog
    super
  end
end

class BundleFixtureSuite
  def initialize(root, runner_class, source_sha)
    @root, @runner_class, @source_sha = root, runner_class, source_sha
    @cases, @assertions, @failures = 0, 0, []
    @process_environment = ENV.to_h.select { |key, _| %w[PATH HOME TMPDIR LANG LC_ALL].include?(key) }
    @process_environment.merge!("GIT_CONFIG_NOSYSTEM" => "1", "GIT_CONFIG_GLOBAL" => File::NULL)
  end

  def check(condition, message)
    @assertions += 1
    raise FixtureFailure, message unless condition
  end

  def rejected(*types)
    types = [ArgumentError, LaneRejected] if types.empty?
    begin
      yield
    rescue *types
      @assertions += 1
      return
    end
    raise FixtureFailure, "Expected rejection"
  end

  def fixture(name)
    @cases += 1
    previous = ENV.to_h
    ENV.replace(@process_environment)
    git("reset", "--hard", @source_sha)
    %w[androidApp androidTvApp build manifest-target bundle-target fixture.jks].each do |relative|
      FileUtils.rm_rf(File.join(@root, relative))
    end
    FileUtils.rm_rf(File.join(@root, "fastlane/metadata"))
    %w[androidApp androidTvApp].each { |relative| FileUtils.mkdir_p(File.join(@root, relative)) }
    File.binwrite(File.join(@root, "fixture.jks"), "disposable keystore bytes")
    ENV.update("SILO_VERSION_NAME" => "1.2.3", "SILO_BUILD_NUMBER" => "2", "PLAY_TRACK" => "beta",
               "SILO_RELEASE_KEYSTORE" => File.join(@root, "fixture.jks"),
               "SILO_RELEASE_KEYSTORE_PASSWORD" => "fixture-password", "SILO_RELEASE_KEY_ALIAS" => "fixture-key",
               "PLAY_RELEASE_NOTES" => "Fixture release notes")
    FixtureUI.messages = []
    runner = @runner_class.new(@root)
    yield runner
  rescue StandardError => error
    @failures << "#{name}: #{error.class}: #{error.message}"
  ensure
    ENV.replace(previous) if previous
  end

  def git(*arguments)
    out, err, status = Open3.capture3(@process_environment, "git", *arguments, chdir: @root, unsetenv_others: true)
    raise FixtureFailure, "Fixture git failed: #{err}" unless status.success?
    out.strip
  end

  def manifest_path
    File.join(@root, PlayBundleManifest::FILE_NAME)
  end

  def expected_gradle(version: "1.2.3", build: 2, track: "beta")
    major, minor, patch = version.split(".").map(&:to_i)
    {
      tasks: [":androidApp:bundleRelease", ":androidTvApp:bundleRelease"],
      properties: {"siloVersionName" => version, "siloVersionCode" => 100_000_000 +
                    (major * 10_000 + minor * 100 + patch) * 1_000 + build,
                   "siloBuildNumber" => build, "siloReleaseChannel" => track},
      system_properties: {"org.gradle.jvmargs" => "-Xmx4g -Dfile.encoding=UTF-8"},
      flags: "--max-workers=2"
    }
  end

  def expected_upload(version: "1.2.3", track: "beta")
    {package_name: "org.siloserver.silo", json_key_data: LaneFixture::KEY_DATA, track: track,
     release_status: "completed", version_name: version,
     aab_paths: [LaneFixture::PHONE, LaneFixture::TV].map { |path| File.join(@root, path) },
     skip_upload_apk: true, skip_upload_metadata: true, skip_upload_images: true,
     skip_upload_screenshots: true, skip_upload_changelogs: false,
     changes_not_sent_for_review: false, timeout: 900}
  end

  def prepare(runner)
    runner.invoke(:prepare_bundles)
    trust_manifest unless ENV["GITHUB_RUN_ID"].to_s.empty?
    runner.events.clear
    runner.gradle_calls.clear
  end

  def trust_manifest
    ENV["SILO_PLAY_BUNDLE_MANIFEST_SHA256"] = Digest::SHA256.file(manifest_path).hexdigest
  end

  def upload_rejected(runner)
    rejected { runner.invoke(:upload_bundles) }
    check(runner.events.empty? && runner.uploads.empty?, "Invalid handoff reached credentials or actions")
  end

  def change_manifest
    value = JSON.parse(File.read(manifest_path))
    yield value
    File.write(manifest_path, JSON.generate(value))
  end

  def ci_context(attempt = "1")
    {"GITHUB_REPOSITORY" => "Silo-Server/silo-android", "GITHUB_REPOSITORY_ID" => "7001",
     "GITHUB_RUN_ID" => "9001", "GITHUB_RUN_ATTEMPT" => attempt, "GITHUB_SHA" => @source_sha}
  end

  def artifact
    {"id" => 8001, "name" => "android-play-bundles-9001-1", "expired" => false,
     "size_in_bytes" => 1000, "workflow_run" => {"id" => 9001, "repository_id" => 7001,
     "head_repository_id" => 7001, "head_sha" => @source_sha}}
  end

  def verify_artifact(value = artifact, **overrides)
    PlayBundleManifest.verify_artifact!(artifact: value, artifact_id: "8001",
      artifact_name: "android-play-bundles-9001-1", producer_attempt: "1", context: ci_context, **overrides)
  end

  def run
    fixture("preparation action and manifest contract") do |runner|
      runner.invoke(:prepare_bundles)
      check(runner.gradle_calls == [expected_gradle], "Preparation changed the Gradle contract")
      check(runner.events == %i[signing_env keystore fcm gradle], "Preparation reached publishing work")
      check(runner.uploads.empty? && !File.exist?(File.join(@root, "fastlane/metadata")), "Preparation wrote publishing data")
      value = PlayBundleManifest.verify!(root: @root, version: "1.2.3", base_code: 110_203_002, build: 2, track: "beta")
      check(value["source_sha"] == @source_sha && value.values_at("repository", "run_id", "producer_attempt") == [nil, nil, nil],
            "Local identity was not bound to actual source")
      check(value["bundles"].map { |entry| entry["path"] } == [LaneFixture::PHONE, LaneFixture::TV], "Wrong bundle paths")
    end

    fixture("upload uses both verified bundles without build secrets") do |runner|
      prepare(runner)
      ENV.keys.select { |key| key.start_with?("SILO_RELEASE_", "SILO_GOOGLE_SERVICES_") }.each { |key| ENV.delete(key) }
      runner.invoke(:upload_bundles)
      check(runner.events == %i[publisher_key changelog upload], "Upload performed preparation work")
      check(runner.gradle_calls.empty? && runner.uploads == [expected_upload], "Upload changed its two-bundle options")
      check(File.read(File.join(@root, "fastlane/metadata/android/en-US/changelogs/default.txt")) == "Fixture release notes",
            "Upload changed changelog bytes")
    end
    fixture("normalized nondefault track survives preparation and upload") do |runner|
      ENV["PLAY_TRACK"] = " ALPHA "
      runner.invoke(:prepare_bundles)
      check(runner.gradle_calls == [expected_gradle(track: "alpha")], "Preparation changed the selected channel")
      runner.events.clear
      runner.invoke(:upload_bundles)
      check(runner.uploads == [expected_upload(track: "alpha")], "Upload changed the prepared track")
    end

    fixture("beta preserves early publisher lookup and one build/upload") do |runner|
      runner.invoke(:beta)
      check(runner.events == %i[publisher_key signing_env keystore fcm changelog gradle upload], "Beta changed baseline action order")
      check(runner.gradle_calls == [expected_gradle] && runner.uploads == [expected_upload], "Beta duplicated or changed actions")
      check(runner.messages_before_gradle.include?([:message,
            "Releasing Silo 1.2.3 build 2 to the beta track (base code 110203002 → phone 220406004, TV 220406005)"]),
            "Beta changed or delayed its baseline release message")
      check(FixtureUI.messages.last == [:success,
            "Uploaded Silo 1.2.3 build 2 (phone 220406004 + TV 220406005) to the beta track."],
            "Beta changed its baseline success message")
    end
    fixture("beta uses its fresh manifest digest") do |runner|
      ENV["SILO_PLAY_BUNDLE_MANIFEST_SHA256"] = "0" * 64
      runner.invoke(:beta)
      check(runner.events == %i[publisher_key signing_env keystore fcm changelog gradle upload], "Beta changed baseline action order")
      check(runner.gradle_calls == [expected_gradle] && runner.uploads == [expected_upload], "Beta used a stale caller digest")
    end
    fixture("beta rejects key before preparation") do |runner|
      runner.fail_key = true
      rejected(SyntheticKeyFailure) { runner.invoke(:beta) }
      check(runner.events == [:publisher_key] && runner.uploads.empty?, "Failed key reached build or upload")
    end
    fixture("beta build failure prevents publication") do |runner|
      runner.fail_build = true
      rejected(SyntheticBuildFailure) { runner.invoke(:beta) }
      check(runner.events == %i[publisher_key signing_env keystore fcm changelog gradle] && runner.uploads.empty? &&
            !File.exist?(manifest_path), "Failed build produced a handoff or upload")
      check(File.read(File.join(@root, "fastlane/metadata/android/en-US/changelogs/default.txt")) == "Fixture release notes",
            "Failed beta build lost its baseline prepared changelog")
      check(runner.messages_before_gradle.include?([:message,
            "Releasing Silo 1.2.3 build 2 to the beta track (base code 110203002 → phone 220406004, TV 220406005)"]),
            "Failed beta build lost its baseline release message")
    end
    fixture("beta changelog failure precedes build") do |runner|
      runner.fail_changelog = true
      rejected(SyntheticChangelogFailure) { runner.invoke(:beta) }
      check(runner.events == %i[publisher_key signing_env keystore fcm changelog] &&
            runner.gradle_calls.empty? && runner.uploads.empty? && !File.exist?(manifest_path),
            "Failed beta changelog reached build or upload")
    end
    [LaneFixture::PHONE, LaneFixture::TV].each do |missing|
      fixture("successful action with missing #{missing}") do |runner|
        runner.produce -= [missing]
        rejected { runner.invoke(:prepare_bundles) }
        check(runner.uploads.empty? && !File.exist?(manifest_path), "Missing bundle created an accepted handoff")
      end
    end

    [[nil, "beta"], ["", "beta"], ["internal", "internal"], ["alpha", "alpha"],
     ["beta", "beta"], ["production", "production"], [" BETA ", "beta"]].each do |input, expected|
      fixture("track #{input.inspect}") do |runner|
        input.nil? ? ENV.delete("PLAY_TRACK") : ENV["PLAY_TRACK"] = input
        runner.invoke(:prepare_bundles)
        check(runner.gradle_calls == [expected_gradle(track: expected)], "Track normalization changed")
      end
    end
    ["sideload", "dev", "unknown", "beta; command"].each do |track|
      fixture("invalid track #{track}") do |runner|
        ENV["PLAY_TRACK"] = track
        rejected { runner.invoke(:prepare_bundles) }
        check(runner.events.empty?, "Invalid track reached actions")
      end
    end
    [["0.0.0", "1", 1], ["94.99.99", "999", 999], ["1.2.3", nil, 1], ["1.2.3", "", 1]].each do |version, input, build|
      fixture("valid version/build #{version}/#{input.inspect}") do |runner|
        ENV["SILO_VERSION_NAME"] = version
        input.nil? ? ENV.delete("SILO_BUILD_NUMBER") : ENV["SILO_BUILD_NUMBER"] = input
        runner.invoke(:prepare_bundles)
        check(runner.gradle_calls == [expected_gradle(version: version, build: build)], "Version/build derivation changed")
      end
    end
    ["", "v1.2.3", "1.2.3-rc.1", "95.0.0", "1.100.0", "1.0.100", "1.2.3; command"].each do |version|
      fixture("invalid version #{version.inspect}") do |runner|
        ENV["SILO_VERSION_NAME"] = version
        rejected { runner.invoke(:prepare_bundles) }
        check(runner.events.empty?, "Invalid version reached actions")
      end
    end
    %w[0 1000 -1 1.2 invalid].each do |build|
      fixture("invalid build #{build}") do |runner|
        ENV["SILO_BUILD_NUMBER"] = build
        rejected { runner.invoke(:prepare_bundles) }
        check(runner.events.empty?, "Invalid build reached actions")
      end
    end
    %w[SILO_RELEASE_KEYSTORE_PASSWORD SILO_RELEASE_KEY_ALIAS].product([nil, " "]).each do |name, value|
      fixture("missing signing #{name}/#{value.inspect}") do |runner|
        value.nil? ? ENV.delete(name) : ENV[name] = value
        rejected { runner.invoke(:prepare_bundles) }
        check(runner.events == [:signing_env] && runner.gradle_calls.empty?, "Missing signing input reached Gradle")
      end
    end
    fixture("key password remains optional") do |runner|
      ENV.delete("SILO_RELEASE_KEY_PASSWORD")
      runner.invoke(:prepare_bundles)
      check(runner.gradle_calls == [expected_gradle], "Optional key password blocked preparation")
    end
    fixture("FCM absent warns and remains absent") do |runner|
      runner.invoke(:prepare_bundles)
      check(!File.exist?(File.join(@root, "androidApp/google-services.json")) &&
            FixtureUI.messages.any? { |type, text| type == :important && text.include?("without FCM push support") },
            "Absent optional FCM behavior changed")
    end
    fixture("FCM synthetic input materializes in phone only") do |runner|
      bytes = "{\"fixture\":\"FCM\"}\n"
      ENV["SILO_GOOGLE_SERVICES_JSON_B64"] = Base64.strict_encode64(bytes)
      runner.invoke(:prepare_bundles)
      check(File.binread(File.join(@root, "androidApp/google-services.json")) == bytes &&
            !File.exist?(File.join(@root, "androidTvApp/google-services.json")), "FCM materialization changed")
    end
    fixture("existing FCM configuration is preserved") do |runner|
      FileUtils.mkdir_p(File.join(@root, "androidApp"))
      File.binwrite(File.join(@root, "androidApp/google-services.json"), "existing fixture FCM")
      ENV["SILO_GOOGLE_SERVICES_JSON_B64"] = Base64.strict_encode64("replacement fixture FCM")
      runner.invoke(:prepare_bundles)
      check(File.binread(File.join(@root, "androidApp/google-services.json")) == "existing fixture FCM", "Existing FCM was replaced")
    end
    fixture("changelog truncation") do |runner|
      prepare(runner)
      ENV["PLAY_RELEASE_NOTES"] = "n" * 501
      runner.invoke(:upload_bundles)
      check(File.read(File.join(@root, "fastlane/metadata/android/en-US/changelogs/default.txt")) == "n" * 500,
            "Changelog length changed")
    end
    fixture("changelog fallback is inert") do |runner|
      prepare(runner)
      ENV.delete("PLAY_RELEASE_NOTES")
      runner.invoke(:upload_bundles)
      check(runner.events == %i[publisher_key changelog changelog_fallback upload] &&
            File.read(File.join(@root, "fastlane/metadata/android/en-US/changelogs/default.txt")) == "Fixture commit subject",
            "Changelog fallback changed")
    end

    [LaneFixture::PHONE, LaneFixture::TV].product(%w[missing empty corrupt]).each do |relative, defect|
      fixture("handoff #{defect} #{relative}") do |runner|
        prepare(runner)
        path = File.join(@root, relative)
        defect == "missing" ? File.unlink(path) : File.binwrite(path, defect == "empty" ? "" : "corrupt fixture")
        upload_rejected(runner)
      end
    end
    fixture("handoff swapped phone and TV bytes") do |runner|
      prepare(runner)
      phone, tv = [LaneFixture::PHONE, LaneFixture::TV].map { |relative| File.join(@root, relative) }
      first, second = File.binread(phone), File.binread(tv)
      File.binwrite(phone, second)
      File.binwrite(tv, first)
      upload_rejected(runner)
    end
    {"schema" => 2, "source_sha" => "0" * 40, "version" => "1.2.4", "base_code" => 110_203_003,
     "build" => 3, "track" => "alpha", "repository" => "other/repo", "run_id" => 1,
     "producer_attempt" => 1, "unexpected" => true}.each do |key, value|
      fixture("manifest mismatch #{key}") do |runner|
        prepare(runner)
        change_manifest { |manifest| manifest[key] = value }
        upload_rejected(runner)
      end
    end
    %w[missing malformed null array empty oversized symlink unexpected-path extra-bundle].each do |defect|
      fixture("manifest #{defect}") do |runner|
        prepare(runner)
        case defect
        when "missing" then File.unlink(manifest_path)
        when "malformed" then File.write(manifest_path, "{")
        when "null" then File.write(manifest_path, "null")
        when "array" then File.write(manifest_path, "[]")
        when "empty" then File.write(manifest_path, "")
        when "oversized" then File.write(manifest_path, " " * 65_537)
        when "symlink"
          FileUtils.mv(manifest_path, File.join(@root, "manifest-target"))
          File.symlink(File.join(@root, "manifest-target"), manifest_path)
        when "unexpected-path" then change_manifest { |value| value["bundles"][0]["path"] = "other.aab" }
        when "extra-bundle" then change_manifest { |value| value["bundles"] << value["bundles"].first.dup }
        end
        upload_rejected(runner)
      end
    end
    fixture("bundle file symlink") do |runner|
      prepare(runner)
      path = File.join(@root, LaneFixture::PHONE)
      FileUtils.mv(path, File.join(@root, "bundle-target"))
      File.symlink(File.join(@root, "bundle-target"), path)
      upload_rejected(runner)
    end
    fixture("bundle ancestor symlink") do |runner|
      prepare(runner)
      path = File.join(@root, "androidApp/build/outputs/bundle")
      FileUtils.mv(path, File.join(@root, "bundle-target"))
      File.symlink(File.join(@root, "bundle-target"), path)
      upload_rejected(runner)
    end
    fixture("manifest parent symlink") do |runner|
      prepare(runner)
      FileUtils.mv(File.join(@root, "build"), File.join(@root, "manifest-target"))
      File.symlink(File.join(@root, "manifest-target"), File.join(@root, "build"))
      upload_rejected(runner)
    end
    fixture("checkout changed after preparation") do |runner|
      prepare(runner)
      File.write(File.join(@root, "fixture-source.txt"), "changed source")
      git("add", "fixture-source.txt")
      git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
          "commit", "--quiet", "-m", "Changed fixture source")
      upload_rejected(runner)
    end

    fixture("same-attempt CI handoff") do |runner|
      ENV.update(ci_context)
      prepare(runner)
      ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
      runner.invoke(:upload_bundles)
      check(runner.uploads == [expected_upload], "Valid CI handoff rejected")
    end
    [false, true].each do |staged|
      fixture("CI preparation rejects #{staged ? 'staged' : 'unstaged'} tracked changes") do |runner|
        ENV.update(ci_context)
        File.write(File.join(@root, "fixture-source.txt"), "modified tracked build input")
        git("add", "fixture-source.txt") if staged
        rejected { runner.invoke(:prepare_bundles) }
        check(runner.events.empty? && runner.gradle_calls.empty? && !File.exist?(manifest_path) && runner.uploads.empty?,
              "Known dirty CI source reached credentials, build or handoff")
      end
      fixture("CI upload rejects #{staged ? 'staged' : 'unstaged'} tracked changes") do |runner|
        ENV.update(ci_context)
        prepare(runner)
        File.write(File.join(@root, "fixture-source.txt"), "modified tracked build input")
        git("add", "fixture-source.txt") if staged
        ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
        upload_rejected(runner)
      end
      fixture("local beta accepts #{staged ? 'staged' : 'unstaged'} tracked changes") do |runner|
        File.write(File.join(@root, "fixture-source.txt"), "modified local source")
        git("add", "fixture-source.txt") if staged
        runner.invoke(:beta)
        check(runner.gradle_calls == [expected_gradle] && runner.uploads == [expected_upload],
              "Local beta lost its dirty-checkout compatibility")
      end
    end
    fixture("CI permits ignored build FCM and changelog files") do |runner|
      ENV.update(ci_context)
      prepare(runner)
      paths = [LaneFixture::PHONE, LaneFixture::TV, PlayBundleManifest::FILE_NAME,
               "androidApp/google-services.json", "fastlane/metadata/android/en-US/changelogs/default.txt"]
      paths.last(2).each do |relative|
        path = File.join(@root, relative)
        FileUtils.mkdir_p(File.dirname(path))
        File.write(path, "owned ignored fixture input")
      end
      check(git("check-ignore", *paths).lines.map(&:chomp).sort == paths.sort,
            "Positive CI fixture did not exercise ignored release outputs")
      check(git("status", "--porcelain=v1", "--untracked-files=no").empty?, "Positive CI fixture changed tracked source")
      ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
      runner.invoke(:upload_bundles)
      check(runner.uploads == [expected_upload], "Ignored release outputs blocked a valid CI handoff")
    end
    fixture("earlier producer accepted by later consumer") do |runner|
      ENV.update(ci_context)
      prepare(runner)
      ENV["GITHUB_RUN_ATTEMPT"] = "3"
      ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
      runner.invoke(:upload_bundles)
      check(runner.uploads == [expected_upload] && verify_artifact(context: ci_context("3")), "Prior producer handoff rejected")
    end
    [nil, "", "invalid", "A" * 64, "0" * 64].each do |digest|
      fixture("CI manifest digest rejected #{digest.inspect}") do |runner|
        ENV.update(ci_context)
        prepare(runner)
        digest.nil? ? ENV.delete("SILO_PLAY_BUNDLE_MANIFEST_SHA256") : ENV["SILO_PLAY_BUNDLE_MANIFEST_SHA256"] = digest
        ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
        upload_rejected(runner)
      end
    end
    fixture("CI rejects changed manifest whitespace") do |runner|
      ENV.update(ci_context)
      prepare(runner)
      original = File.read(manifest_path)
      File.write(manifest_path, original + " ")
      check(JSON.parse(File.read(manifest_path)) == JSON.parse(original), "Whitespace fixture changed parsed metadata")
      ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
      upload_rejected(runner)
    end
    [LaneFixture::PHONE, LaneFixture::TV].each do |relative|
      fixture("CI rejects rewritten bundle and matching manifest #{relative}") do |runner|
        ENV.update(ci_context)
        prepare(runner)
        producer_digest = ENV.fetch("SILO_PLAY_BUNDLE_MANIFEST_SHA256")
        File.binwrite(File.join(@root, relative), "rewritten fixture bundle")
        change_manifest do |value|
          record = value["bundles"].find { |entry| entry["path"] == relative }
          record["bytes"] = File.size(File.join(@root, relative))
          record["sha256"] = Digest::SHA256.file(File.join(@root, relative)).hexdigest
        end
        check(Digest::SHA256.file(manifest_path).hexdigest != producer_digest, "Tamper fixture kept producer digest")
        ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
        upload_rejected(runner)
      end
    end
    fixture("local absent digest keeps compatible manifest parsing") do |runner|
      prepare(runner)
      File.open(manifest_path, "a") { |file| file.write(" ") }
      check(ENV["SILO_PLAY_BUNDLE_MANIFEST_SHA256"].nil?, "Local fixture unexpectedly had a producer digest")
      runner.invoke(:upload_bundles)
      check(runner.events == %i[publisher_key changelog upload] && runner.uploads == [expected_upload],
            "Local upload requires a CI digest")
    end
    fixture("earlier producer requires an explicit retained attempt") do |runner|
      ENV.update(ci_context)
      prepare(runner)
      ENV["GITHUB_RUN_ATTEMPT"] = "3"
      ENV.delete("SILO_PLAY_BUNDLE_ATTEMPT")
      upload_rejected(runner)
    end
    ["", "0", "2", "01", "invalid"].each do |attempt|
      fixture("CI invalid producer attempt #{attempt.inspect}") do |runner|
        ENV.update(ci_context)
        prepare(runner)
        ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = attempt
        upload_rejected(runner)
      end
    end
    {"GITHUB_REPOSITORY" => "other/repo", "GITHUB_RUN_ID" => "9002", "GITHUB_SHA" => "0" * 40}.each do |key, value|
      fixture("CI changed #{key}") do |runner|
        ENV.update(ci_context)
        prepare(runner)
        ENV[key] = value
        ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
        upload_rejected(runner)
      end
    end
    %w[GITHUB_REPOSITORY GITHUB_RUN_ID GITHUB_RUN_ATTEMPT GITHUB_SHA].each do |missing|
      fixture("partial CI context missing #{missing}") do |runner|
        ENV.update(ci_context)
        ENV.delete(missing)
        rejected { runner.invoke(:prepare_bundles) }
        check(runner.events.empty? && runner.gradle_calls.empty? && !File.exist?(manifest_path) && runner.uploads.empty?,
              "Partial CI context reached credentials, build or handoff")
      end
    end

    fixture("artifact identity positive") { check(verify_artifact, "Valid API artifact rejected") }
    [nil, "", "0", "01", "invalid"].each do |id|
      fixture("artifact bad selector #{id.inspect}") { rejected { verify_artifact(artifact_id: id) } }
    end
    {"id" => 8002, "name" => "wrong", "expired" => true, "size_in_bytes" => 0,
     "workflow_run" => nil}.each do |key, value|
      fixture("artifact mismatch #{key}") do
        value_with_defect = artifact
        value_with_defect[key] = value
        rejected { verify_artifact(value_with_defect) }
      end
    end
    {"id" => 9002, "repository_id" => 7002, "head_repository_id" => 7002, "head_sha" => "0" * 40}.each do |key, value|
    fixture("artifact workflow mismatch #{key}") do
        value_with_defect = artifact
        value_with_defect["workflow_run"][key] = value
        rejected { verify_artifact(value_with_defect) }
      end
    end
    %w[schema run_id producer_attempt base_code build].each do |key|
      fixture("manifest noninteger #{key}") do |runner|
        ENV.update(ci_context)
        prepare(runner)
        change_manifest { |value| value[key] = value.fetch(key).to_f }
        trust_manifest
        ENV["SILO_PLAY_BUNDLE_ATTEMPT"] = "1"
        upload_rejected(runner)
      end
    end
    fixture("manifest noninteger bundle bytes") do |runner|
      prepare(runner)
      change_manifest { |value| value["bundles"][0]["bytes"] = value["bundles"][0]["bytes"].to_f }
      upload_rejected(runner)
    end
    %w[id repository_id head_repository_id].each do |key|
      [nil, true, :string, :float].each do |invalid|
        fixture("artifact workflow noninteger #{key}/#{invalid.inspect}") do
          value = artifact
          original = value["workflow_run"].fetch(key)
          value["workflow_run"][key] = case invalid
                                      when :float then original.to_f
                                      when :string then original.to_s
                                      else invalid
                                      end
          rejected { verify_artifact(value) }
        end
      end
    end
    [nil, true, "8001", 8001.0].each do |invalid|
      fixture("artifact ID noninteger #{invalid.inspect}") do
        value = artifact
        value["id"] = invalid
        rejected { verify_artifact(value) }
      end
    end
    fixture("artifact wrong output name") { rejected { verify_artifact(artifact_name: "android-play-bundles-9001-2") } }
    fixture("artifact future producer") { rejected { verify_artifact(producer_attempt: "2") } }
    fixture("earlier artifact requires exact producer name and ID") do
      rejected { verify_artifact(producer_attempt: "2", context: ci_context("3")) }
      rejected { verify_artifact(artifact_name: "android-play-bundles-9001-2", context: ci_context("3")) }
      rejected { verify_artifact(artifact_id: "8002", context: ci_context("3")) }
    end
    [nil, "false"].each do |invalid|
      fixture("artifact expired flag type #{invalid.inspect}") do
        value = artifact
        value["expired"] = invalid
        rejected { verify_artifact(value) }
      end
    end
    ["1000", 1000.0, -1, 2_147_483_649].each do |invalid|
      fixture("artifact size rejected #{invalid.inspect}") do
        value = artifact
        value["size_in_bytes"] = invalid
        rejected { verify_artifact(value) }
      end
    end
    [nil, [], "metadata"].each do |value|
      fixture("artifact malformed #{value.inspect}") { rejected { verify_artifact(value) } }
    end
    fixture("artifact CLI bounded metadata") do
      env = ci_context.merge("PLAY_BUNDLE_ARTIFACT_ID" => "8001", "PLAY_BUNDLE_ARTIFACT_NAME" => "android-play-bundles-9001-1",
                             "SILO_PLAY_BUNDLE_ATTEMPT" => "1")
      command = [RbConfig.ruby, File.join(@root, "fastlane/play_bundle_manifest.rb"), "verify-artifact"]
      out, err, status = Open3.capture3(env, *command, stdin_data: JSON.generate(artifact))
      check(status.success? && out == "Verified Play bundle artifact provenance\n" && err.empty?, "Valid artifact CLI rejected")
      ["{", "x" * 65_537].each do |input|
        out, err, status = Open3.capture3(env, *command, stdin_data: input)
        check(!status.success? && out.empty? && err == "Play bundle artifact verification failed\n", "Malformed CLI metadata accepted or exposed")
      end
      out, err, status = Open3.capture3(env.merge("PLAY_BUNDLE_ARTIFACT_ID" => nil), *command,
                                      stdin_data: JSON.generate(artifact))
      check(!status.success? && out.empty? && err == "Play bundle artifact verification failed\n", "Missing CLI selector accepted")
    end

    unless @failures.empty?
      warn @failures.first(20).map { |message| "FAIL: #{message}" }.join("\n")
      warn "#{@failures.length} Play bundle fixture(s) failed"
      exit 1
    end
    puts "All Play bundle lane/manifest fixtures passed (#{@cases} cases; #{@assertions} assertions; no native build or publishing)"
  end
end

repo_root = File.expand_path("..", __dir__)
Dir.mktmpdir("play-bundle-lanes-") do |root|
  FileUtils.mkdir_p(File.join(root, "fastlane"))
  %w[Fastfile play_bundle_manifest.rb].each do |name|
    FileUtils.cp(File.join(repo_root, "fastlane", name), File.join(root, "fastlane", name))
  end
  File.write(File.join(root, "fixture-source.txt"), "original fixture source")
  File.write(File.join(root, ".gitignore"), "**/build/\nandroidApp/google-services.json\nfastlane/metadata/\n")
  git_env = ENV.to_h.select { |key, _| %w[PATH HOME TMPDIR LANG LC_ALL].include?(key) }
  git_env.merge!("GIT_CONFIG_NOSYSTEM" => "1", "GIT_CONFIG_GLOBAL" => File::NULL)
  [["init", "--quiet"], ["config", "core.hooksPath", File.join(root, "empty-hooks")], ["add", "."],
   ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
    "commit", "--quiet", "-m", "Fixture source"]].each do |arguments|
    _out, err, status = Open3.capture3(git_env, "git", *arguments, chdir: root, unsetenv_others: true)
    raise FixtureFailure, "Fixture initialization failed: #{err}" unless status.success?
  end
  source_sha, status = Open3.capture2(git_env, "git", "rev-parse", "HEAD", chdir: root, unsetenv_others: true)
  raise FixtureFailure, "Fixture source unavailable" unless status.success?
  runner_class = Class.new(LaneFixture)
  fastfile = File.join(root, "fastlane/Fastfile")
  runner_class.class_eval(File.read(fastfile), fastfile, 1)
  runner_class.prepend(FixtureHelpers)
  BundleFixtureSuite.new(root, runner_class, source_sha.strip).run
end
