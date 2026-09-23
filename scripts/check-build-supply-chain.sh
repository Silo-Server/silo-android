#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ruby - "${repo_root}" <<'RUBY'
require "psych"
require "rexml/document"

repo_root = ARGV.fetch(0)
failures = []

relative_path = lambda do |path|
  path.delete_prefix("#{repo_root}/")
end

inspect_uses = lambda do |node, workflow_path|
  case node
  when Hash
    node.each do |key, value|
      if key.to_s == "uses"
        reference = value.to_s
        # First-party Silo-Server workflows track their branch so every
        # client repo shares the same version; only third-party refs need SHAs.
        unless reference.start_with?("./") ||
               reference.match?(/\ASilo-Server\/[^@\s]+@[^@\s]+\z/i) ||
               reference.match?(/\A[^@\s]+@[0-9a-fA-F]{40}\z/)
          failures << "Unpinned GitHub Action in " \
                      "#{relative_path.call(workflow_path)}: #{reference}"
        end
      end
      inspect_uses.call(value, workflow_path)
    end
  when Array
    node.each { |value| inspect_uses.call(value, workflow_path) }
  end
end

workflow_paths = Dir[
  File.join(repo_root, ".github", "workflows", "*.{yml,yaml}")
].sort
workflow_paths.each do |workflow_path|
  begin
    workflow = Psych.safe_load(
      File.read(workflow_path),
      permitted_classes: [],
      permitted_symbols: [],
      aliases: true,
      filename: workflow_path
    )
    inspect_uses.call(workflow, workflow_path)
  rescue Psych::Exception => error
    failures << "Invalid workflow YAML in " \
                "#{relative_path.call(workflow_path)}: #{error.message}"
  end
end

wrapper_properties = File.join(
  repo_root,
  "gradle",
  "wrapper",
  "gradle-wrapper.properties"
)
wrapper_contents = File.exist?(wrapper_properties) ? File.read(wrapper_properties) : ""
unless wrapper_contents.match?(/^distributionSha256Sum=[0-9a-fA-F]{64}$/)
  failures << "Missing or invalid Gradle wrapper distributionSha256Sum in " \
              "#{relative_path.call(wrapper_properties)}"
end

verification_metadata = File.join(repo_root, "gradle", "verification-metadata.xml")
metadata_errors = []

begin
  raise Errno::ENOENT, verification_metadata unless File.file?(verification_metadata)

  document = REXML::Document.new(File.read(verification_metadata))
  root = document.root
  metadata_errors << "unexpected root element" unless root&.name == "verification-metadata"

  elements = []
  walk_elements = lambda do |element|
    elements << element
    element.each_element { |child| walk_elements.call(child) }
  end
  walk_elements.call(root) if root

  configuration = root&.elements&.to_a&.find { |element| element.name == "configuration" }
  verify_metadata = configuration&.elements&.to_a&.find do |element|
    element.name == "verify-metadata"
  end
  unless verify_metadata&.text.to_s.strip == "true"
    metadata_errors << "metadata verification is not enabled"
  end

  components = elements.select { |element| element.name == "component" }
  artifacts = components.flat_map do |component|
    component.elements.to_a.select { |element| element.name == "artifact" }
  end
  checksums_by_artifact = artifacts.map do |artifact|
    artifact.elements.to_a.select { |element| element.name == "sha256" }
  end
  checksums = checksums_by_artifact.flatten
  metadata_errors << "no components" if components.empty?
  metadata_errors << "no artifacts" if artifacts.empty?
  metadata_errors << "no SHA-256 checksums" if checksums.empty?
  if checksums_by_artifact.any?(&:empty?)
    metadata_errors << "artifact without a SHA-256 checksum"
  end
  unless checksums.all? do |checksum|
    checksum.attributes["value"].to_s.match?(/\A[0-9a-fA-F]{64}\z/)
  end
    metadata_errors << "invalid SHA-256 checksum"
  end

  components
    .select do |component|
      component.attributes["group"] == "com.android.tools.build" &&
        component.attributes["name"] == "aapt2"
    end
    .each do |component|
      version = component.attributes["version"].to_s
      expected = "aapt2-#{version}-linux.jar"
      names = component.elements
        .to_a
        .select { |element| element.name == "artifact" }
        .map { |artifact| artifact.attributes["name"].to_s }
      unless names.include?(expected)
        metadata_errors << "Missing Linux AAPT2 verification artifact #{expected}"
      end
    end

  trust_all = elements.any? do |element|
    next true if element.name == "trust-all"
    next false unless element.name == "trust"

    selectors = %w[group name version file].map do |attribute|
      element.attributes[attribute]
    end.compact
    next true if selectors.empty?

    universal = /\A(?:|\*|\.\*|\^\.\*\$)\z/
    element.attributes["regex"].to_s == "true" &&
      selectors.all? { |selector| selector.match?(universal) }
  end
  metadata_errors << "trust-all rule is not allowed" if trust_all
rescue Errno::ENOENT, REXML::ParseException => error
  metadata_errors << error.message
end

unless metadata_errors.empty?
  failures << "Missing or invalid dependency verification metadata: " \
              "#{relative_path.call(verification_metadata)} " \
              "(#{metadata_errors.join('; ')})"
end

unless failures.empty?
  warn failures.join("\n")
  exit 1
end
RUBY
