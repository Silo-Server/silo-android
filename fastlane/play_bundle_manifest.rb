require "digest"
require "fileutils"
require "json"
require "open3"

# The release producer and consumer exchange these two final files in one run.
# APK preparation remains separate because bundle task names disable APK splits.
module PlayBundleManifest
  FILE_NAME = "build/play-bundles-manifest.json"
  PATHS = %w[
    androidApp/build/outputs/bundle/release/androidApp-release.aab
    androidTvApp/build/outputs/bundle/release/androidTvApp-release.aab
  ].freeze
  MAX_FILE_BYTES = 1_073_741_824
  MAX_MANIFEST_BYTES = 65_536
  REPOSITORY = "Silo-Server/silo-android"

  def self.require!(condition, message)
    raise ArgumentError, message unless condition
  end

  def self.positive_integer(value, name)
    require!(value.to_s.match?(/\A[1-9]\d*\z/), "Invalid #{name}")
    value.to_i
  end

  def self.identity(root, context)
    sha, status = Open3.capture2("git", "rev-parse", "HEAD", chdir: root)
    sha = sha.strip
    require!(status.success? && sha.match?(/\A[0-9a-f]{40}\z/), "Source revision unavailable")
    names = %w[GITHUB_REPOSITORY GITHUB_RUN_ID GITHUB_RUN_ATTEMPT GITHUB_SHA]
    return {"source_sha" => sha, "repository" => nil, "run_id" => nil, "producer_attempt" => nil} if
      names.all? { |name| context[name].to_s.empty? }

    require!(context["GITHUB_REPOSITORY"] == REPOSITORY && context["GITHUB_SHA"] == sha,
             "Bundle source does not match this release run")
    tracked, tracked_status = Open3.capture2("git", "status", "--porcelain=v1", "--untracked-files=no",
                                           "--ignore-submodules=none", chdir: root)
    require!(tracked_status.success? && tracked.empty?, "CI bundle source has tracked changes")
    {"source_sha" => sha, "repository" => REPOSITORY,
     "run_id" => positive_integer(context["GITHUB_RUN_ID"], "run ID"),
     "producer_attempt" => positive_integer(context["GITHUB_RUN_ATTEMPT"], "run attempt")}
  end

  def self.bundle_record(root, relative)
    root = File.realpath(root)
    path = File.join(root, relative)
    components = relative.split("/")
    require!(components.each_index.none? { |index| File.symlink?(File.join(root, *components[0..index])) },
             "Bundle path must not contain a symlink")
    require!(File.file?(path) && File.size(path).between?(1, MAX_FILE_BYTES),
             "Expected nonempty App Bundle not found")
    {"path" => relative, "bytes" => File.size(path), "sha256" => Digest::SHA256.file(path).hexdigest}
  end

  def self.write!(root:, version:, base_code:, build:, track:, context: ENV)
    manifest = {"schema" => 1, **identity(root, context), "version" => version,
                "base_code" => base_code, "build" => build, "track" => track,
                "bundles" => PATHS.map { |path| bundle_record(root, path) }}
    path = File.join(root, FILE_NAME)
    require!(!File.symlink?(File.dirname(path)) && !File.symlink?(path), "Manifest must not be a symlink")
    FileUtils.mkdir_p(File.dirname(path))
    File.write(path, JSON.pretty_generate(manifest) + "\n")
    manifest
  end

  def self.verify!(root:, version:, base_code:, build:, track:, context: ENV, producer_attempt: nil, manifest_sha256: nil)
    path = File.join(root, FILE_NAME)
    require!(!File.symlink?(File.dirname(path)) && !File.symlink?(path) &&
             File.file?(path) && File.size(path).between?(1, MAX_MANIFEST_BYTES),
             "Play bundle manifest is missing or invalid")
    source = identity(root, context)
    if source["run_id"] || manifest_sha256
      require!(manifest_sha256.is_a?(String) && manifest_sha256.match?(/\A[0-9a-f]{64}\z/) &&
               Digest::SHA256.file(path).hexdigest == manifest_sha256,
               "Play bundle manifest does not match its producer")
    end
    manifest = JSON.parse(File.read(path))
    require!(manifest.is_a?(Hash) &&
             %w[schema base_code build].all? { |key| manifest[key].is_a?(Integer) } &&
             %w[run_id producer_attempt].all? { |key| manifest[key].nil? || manifest[key].is_a?(Integer) } &&
             manifest["bundles"].is_a?(Array) && manifest["bundles"].all? { |bundle|
               bundle.is_a?(Hash) && bundle["bytes"].is_a?(Integer)
             }, "Play bundle manifest has invalid field types")
    expected = {"schema" => 1, **source, "version" => version,
                "base_code" => base_code, "build" => build, "track" => track,
                "bundles" => PATHS.map { |relative| bundle_record(root, relative) }}
    if expected["run_id"]
      attempt = positive_integer(producer_attempt || expected["producer_attempt"], "producer attempt")
      require!(attempt <= expected["producer_attempt"], "Producer attempt is newer than this consumer")
      expected["producer_attempt"] = attempt
    else
      require!(producer_attempt.nil?, "Local bundle has no CI producer")
    end
    require!(manifest == expected, "Prepared Play bundles do not match this release")
    manifest
  rescue JSON::ParserError
    raise ArgumentError, "Play bundle manifest is malformed"
  end

  def self.verify_artifact!(artifact:, artifact_id:, artifact_name:, producer_attempt:, context: ENV)
    id = positive_integer(artifact_id, "artifact ID")
    attempt = positive_integer(producer_attempt, "producer attempt")
    run = positive_integer(context["GITHUB_RUN_ID"], "run ID")
    current_attempt = positive_integer(context["GITHUB_RUN_ATTEMPT"], "run attempt")
    repository_id = positive_integer(context["GITHUB_REPOSITORY_ID"], "repository ID")
    require!(context["GITHUB_REPOSITORY"] == REPOSITORY &&
             context["GITHUB_SHA"].to_s.match?(/\A[0-9a-f]{40}\z/) && attempt <= current_attempt,
             "Unexpected release artifact context")
    expected_name = "android-play-bundles-#{run}-#{attempt}"
    workflow = artifact.is_a?(Hash) ? artifact["workflow_run"] : nil
    require!(artifact_name == expected_name && artifact.is_a?(Hash) &&
             artifact["id"].is_a?(Integer) && artifact["id"] == id &&
             artifact["name"] == expected_name && artifact["expired"] == false &&
             artifact["size_in_bytes"].is_a?(Integer) && artifact["size_in_bytes"].between?(1, 2 * MAX_FILE_BYTES) &&
             workflow.is_a?(Hash) &&
             %w[id repository_id head_repository_id].all? { |key| workflow[key].is_a?(Integer) } &&
             workflow["id"] == run && workflow["repository_id"] == repository_id &&
             workflow["head_repository_id"] == repository_id &&
             workflow["head_sha"] == context["GITHUB_SHA"],
             "Artifact does not belong to this release producer")
    true
  end
end

if $PROGRAM_NAME == __FILE__
  begin
    raise ArgumentError, "Unsupported manifest operation" unless ARGV == ["verify-artifact"]
    input = STDIN.read(PlayBundleManifest::MAX_MANIFEST_BYTES + 1)
    raise ArgumentError, "Artifact metadata exceeds its limit" if input.bytesize > PlayBundleManifest::MAX_MANIFEST_BYTES
    PlayBundleManifest.verify_artifact!(
      artifact: JSON.parse(input), artifact_id: ENV.fetch("PLAY_BUNDLE_ARTIFACT_ID", ""),
      artifact_name: ENV.fetch("PLAY_BUNDLE_ARTIFACT_NAME", ""),
      producer_attempt: ENV.fetch("SILO_PLAY_BUNDLE_ATTEMPT", "")
    )
    puts "Verified Play bundle artifact provenance"
  rescue ArgumentError, JSON::ParserError
    warn "Play bundle artifact verification failed"
    exit 1
  end
end
