#!/usr/bin/env ruby
# Runs the real Fastfile's lanes against a stand-in for fastlane, checking the
# arguments each lane passes to Google Play. Needs no gems or credentials.

require "fileutils"
require "tmpdir"

class LaneRejected < StandardError; end

class LaneFixture
  module UI
    def self.user_error!(message)
      raise LaneRejected, message
    end
  end

  attr_reader :uploads

  def initialize(fastfile)
    @lanes = {}
    @uploads = []
    instance_eval(File.read(fastfile), fastfile)
  end

  def default_platform(_name); end

  def platform(_name)
    yield
  end

  def desc(_text); end

  def lane(name, &block)
    @lanes[name] = block
  end

  def upload_to_play_store(**options)
    uploads << options
  end

  def run(name)
    instance_exec(&@lanes.fetch(name))
  end
end

REPO = File.expand_path("..", __dir__)
BUNDLES = %w[
  androidApp/build/outputs/bundle/release/androidApp-release.aab
  androidTvApp/build/outputs/bundle/release/androidTvApp-release.aab
].freeze
CHANGELOG = "fastlane/metadata/android/en-US/changelogs/default.txt"
SETTINGS = %w[PLAY_SERVICE_ACCOUNT_JSON PLAY_TRACK PLAY_RELEASE_NAME PLAY_PROMOTE_TO PLAY_VERSION_CODE].freeze

$failures = []

def check(condition, message)
  $failures << message unless condition
end

def rejected?
  yield
  false
rescue LaneRejected
  true
end

# Runs a case in a scratch copy of the repository layout with only `settings` set.
def scenario(name, settings, files)
  saved = ENV.to_h.slice(*SETTINGS)
  SETTINGS.each { |key| ENV.delete(key) }
  ENV.update({"PLAY_SERVICE_ACCOUNT_JSON" => '{"fixture":true}'}.merge(settings))
  Dir.mktmpdir("silo-fastlane-lanes") do |root|
    FileUtils.mkdir_p(File.join(root, "fastlane"))
    FileUtils.cp(File.join(REPO, "fastlane/Fastfile"), File.join(root, "fastlane/Fastfile"))
    files.each do |relative|
      FileUtils.mkdir_p(File.dirname(File.join(root, relative)))
      File.write(File.join(root, relative), "fixture")
    end
    yield LaneFixture.new(File.join(root, "fastlane/Fastfile")), root
  end
rescue StandardError => error
  $failures << "#{name}: #{error.class}: #{error.message}"
ensure
  SETTINGS.each { |key| ENV.delete(key) }
  ENV.update(saved)
end

upload_settings = {"PLAY_TRACK" => "beta", "PLAY_RELEASE_NAME" => "1.1.0 (22)"}

scenario("upload", upload_settings, BUNDLES + [CHANGELOG]) do |lanes, root|
  lanes.run(:upload)
  upload = lanes.uploads.fetch(0)
  check(upload[:package_name] == "org.siloserver.silo", "upload: package name")
  check(upload[:json_key_data] == '{"fixture":true}', "upload: publisher key")
  check(upload[:track] == "beta", "upload: track")
  check(upload[:release_status] == "completed", "upload: release status")
  check(upload[:version_name] == "1.1.0 (22)", "upload: release name")
  check(upload[:aab_paths] == BUNDLES.map { |path| File.join(root, path) }, "upload: both bundles in one release")
  check(!upload.key?(:skip_upload_changelogs), "upload: Play's What's new must be uploaded")
end

scenario("upload without bundles", upload_settings, [CHANGELOG]) do |lanes|
  check(rejected? { lanes.run(:upload) }, "upload: missing bundles must fail")
end

scenario("upload without notes", upload_settings, BUNDLES) do |lanes|
  check(rejected? { lanes.run(:upload) }, "upload: missing release notes must fail")
end

promote_settings = {"PLAY_TRACK" => "beta", "PLAY_PROMOTE_TO" => "production", "PLAY_VERSION_CODE" => "220200044"}

scenario("promote", promote_settings, [CHANGELOG]) do |lanes|
  lanes.run(:promote)
  promote = lanes.uploads.fetch(0)
  check(promote[:track] == "beta", "promote: source track")
  check(promote[:track_promote_to] == "production", "promote: target track")
  check(promote[:track_promote_release_status] == "draft", "promote: lands as a draft")
  check(promote[:version_code] == 220_200_044, "promote: picks the release by version code")
  check(promote[:skip_upload_aab] && promote[:skip_upload_apk] && !promote.key?(:aab_paths),
        "promote: uploads no binaries")
end

scenario("lanes without settings", {}, BUNDLES + [CHANGELOG]) do |lanes|
  check(rejected? { lanes.run(:upload) }, "upload: missing settings must fail")
  check(rejected? { lanes.run(:promote) }, "promote: missing settings must fail")
end

if $failures.empty?
  puts "Fastlane lane tests passed"
else
  warn $failures.map { |message| "FAIL: #{message}" }.join("\n")
  exit 1
end
