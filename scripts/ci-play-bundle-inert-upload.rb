#!/usr/bin/env ruby
require "digest"
require "json"
require "fileutils"

# Private diagnostic controller: load the unchanged upload lane into an inert
# DSL. Fastlane/Supply are never loaded; every unrecognised action is fatal.
module InertUI
  def self.user_error!(_message); raise ArgumentError, "Lane rejected"; end
  %i[message important success].each { |name| define_singleton_method(name) { |_message| } }
end

class InertUpload
  UI = InertUI
  MARKER = "inert diagnostic publisher placeholder"
  class << self
    attr_reader :lanes
    def default_platform(name); raise unless name == :android; end
    def platform(name); raise unless name == :android; yield; end
    def desc(_text); end
    def lane(name, &block); @lanes ||= {}; raise if @lanes.key?(name); @lanes[name] = block; end
  end
  attr_reader :publisher_stub_entries, :actions, :selected
  def initialize
    @publisher_stub_entries, @actions, @selected = 0, 0, []
  end
  def enter_publisher_stub
    @publisher_stub_entries += 1
    MARKER
  end
  def upload_to_play_store(**options)
    raise "Unexpected inert action options" unless options[:json_key_data] == MARKER &&
      options.keys.sort == %i[package_name json_key_data track release_status version_name aab_paths
        skip_upload_apk skip_upload_metadata skip_upload_images skip_upload_screenshots
        skip_upload_changelogs changes_not_sent_for_review timeout].sort &&
      options[:package_name] == "org.siloserver.silo" && options[:track] == "beta" &&
      options[:version_name] == "0.0.2" && options[:changes_not_sent_for_review] == false && options[:timeout] == 900 &&
      options[:release_status] == "completed" && options[:skip_upload_apk] == true &&
      options[:skip_upload_metadata] == true && options[:skip_upload_images] == true &&
      options[:skip_upload_screenshots] == true && options[:skip_upload_changelogs] == false
    @actions += 1
    @selected = options.fetch(:aab_paths).map do |path|
      {"path" => path, "bytes" => File.size(path), "sha256" => Digest::SHA256.file(path).hexdigest}
    end
  end
  def invoke
    instance_exec(&self.class.lanes.fetch(:upload_bundles))
  end
  def method_missing(_name, *_arguments, **_keywords, &_block)
    raise "Unexpected action in inert upload controller"
  end
end

raise "Unsupported diagnostic command" unless ARGV.empty? || ARGV == ["--check-source"]
ROOT = File.realpath(ENV.fetch("SILO_DIAGNOSTIC_ROOT", File.expand_path("..", __dir__)))
EXPECTED_SOURCE = {
  ".github/workflows/release.yml" => "6cd887db72897a1475fa4885652cbf8d73034eb074d7aadd9366f2cb0e2e090a",
  "fastlane/Fastfile" => "56ac960d05f397cc344bf8a5c977a6da2d7908338481279b82e41753c8e07bfe",
  "fastlane/play_bundle_manifest.rb" => "4fa9950d6e036b410a58cb4fd5a1df6b8b66cb253790faa7769e739ab18a4f84",
  "scripts/test-play-bundle-lanes.rb" => "af72495b65893f1f0f889cd929b2a942132d67aab2976fa7a674913f7ffee34e",
  "scripts/test-release-workflow.sh" => "c555cf20cf4bc456afa797221868187f37222bcfa25029348b2b87b93a7d95ea",
  "androidApp/src/androidUnitTest/kotlin/org/siloserver/silo/android/ui/screens/libraries/LibrariesViewModelTest.kt" => "e7e6fb7929edc2be51fc096910fc42489b1e8ac2124e8f3ff5d284f991c3f7e6"
}.freeze
EXPECTED_SOURCE.each { |path, sha| raise "Frozen source changed" unless Digest::SHA256.file(File.join(ROOT, path)).hexdigest == sha }
if ARGV == ["--check-source"]
  puts "Verified frozen e569 source and reviewed scheduler fixture"
  exit 0
end
receipt_path = ENV.fetch("SILO_DIAGNOSTIC_RECEIPT")
instance = nil
status = 1
deliberate = ENV["SILO_DIAGNOSTIC_REJECT_FIRST_DOWNLOAD"] == "true" && ENV["GITHUB_RUN_ATTEMPT"] == "1"
begin
  raise "Publisher credential environment is prohibited" if ENV.key?("PLAY_SERVICE_ACCOUNT_JSON")
  raise "Real Fastlane is prohibited" if defined?(Fastlane)
  raise "Unexpected diagnostic identity" unless ENV["GITHUB_EVENT_NAME"] == "workflow_dispatch" &&
    ENV["GITHUB_WORKFLOW"] == "Android Builds" &&
    ENV["GITHUB_WORKFLOW_REF"].to_s.start_with?("Silo-Server/silo-android/.github/workflows/android-build.yml@")
  raise "Unexpected release inputs" unless ENV["SILO_VERSION_NAME"] == "0.0.2" &&
    ENV["SILO_BUILD_NUMBER"] == "1" && ENV["PLAY_TRACK"] == "beta"
  EXPECTED_SOURCE.each { |path, sha| raise "Frozen source changed" unless Digest::SHA256.file(File.join(ROOT, path)).hexdigest == sha }
  fastfile = File.join(ROOT, "fastlane/Fastfile")
  InertUpload.class_eval(File.read(fastfile, encoding: "UTF-8"), fastfile, 1)
  # Replace the original publisher helper before invoking any lane. This
  # prevents even a local HOME credential lookup, and records the ordering.
  InertUpload.send(:define_method, :play_json_key_data) { enter_publisher_stub }
  instance = InertUpload.new
  if deliberate
    File.delete(File.join(ROOT, PlayBundleManifest::PATHS.last))
  end
  Dir.chdir(ROOT) { instance.invoke }
  raise "Incomplete inert upload" unless instance.publisher_stub_entries == 1 && instance.actions == 1 && instance.selected.size == 2
  raise "Expected first download interruption" if deliberate
  status = 0
rescue StandardError => error
  error_type = error.class.name
ensure
  record = {"schema" => 1, "production_publish" => false, "play_credentials_provided" => ENV.key?("PLAY_SERVICE_ACCOUNT_JSON"),
            "source_sha" => ENV["GITHUB_SHA"], "run_id" => ENV["GITHUB_RUN_ID"],
            "consumer_attempt" => ENV["GITHUB_RUN_ATTEMPT"], "producer_attempt" => ENV["SILO_PLAY_BUNDLE_ATTEMPT"],
            "producer_manifest_sha256" => ENV["SILO_PLAY_BUNDLE_MANIFEST_SHA256"],
            "deliberate_first_download_interruption" => deliberate, "exit_code" => status,
            "error_type" => error_type, "publisher_stub_entries" => instance&.publisher_stub_entries || 0,
            "inert_action_entries" => instance&.actions || 0, "bundles" => instance&.selected || []}
  FileUtils.mkdir_p(File.dirname(receipt_path))
  File.write(receipt_path, JSON.pretty_generate(record) + "\n")
  puts JSON.generate(record)
end
exit status
