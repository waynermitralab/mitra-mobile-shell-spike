# Adds the unit test target of the iOS shell to a generated project: `generate.mjs --ios-tests`
# runs it after `cap add ios`. Usage: ruby generator/ios-tests.rb <App.xcodeproj> <tests dir>
#
# AppTests is a unit test bundle hosted by the app (TEST_HOST), so `@testable import App` reaches the
# real MitraShellPlugin with Capacitor linked as in the app. A shared App scheme gains the test
# action, so `xcodebuild test -scheme App` runs it. The xcodeproj gem is the one CocoaPods ships,
# present on the macOS runners. Only the projects the CI tests carry it: the store build never does.

require "xcodeproj"

project_path, tests_dir = ARGV
abort "usage: ios-tests.rb <App.xcodeproj> <tests dir>" unless project_path && tests_dir && Dir.exist?(tests_dir)

project = Xcodeproj::Project.open(project_path)
app = project.targets.find { |t| t.name == "App" } or abort "no App target in #{project_path}"
abort "AppTests already in #{project_path}" if project.targets.any? { |t| t.name == "AppTests" }

deployment = app.build_configurations.map { |c| c.build_settings["IPHONEOS_DEPLOYMENT_TARGET"] }.compact.first ||
             project.build_configurations.map { |c| c.build_settings["IPHONEOS_DEPLOYMENT_TARGET"] }.compact.first
bundle_id = app.build_configurations.map { |c| c.build_settings["PRODUCT_BUNDLE_IDENTIFIER"] }.compact.first

tests = project.new_target(:unit_test_bundle, "AppTests", :ios, deployment)
tests.add_dependency(app)
tests.build_configurations.each do |config|
  s = config.build_settings
  s["TEST_HOST"] = "$(BUILT_PRODUCTS_DIR)/App.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/App"
  s["BUNDLE_LOADER"] = "$(TEST_HOST)"
  s["PRODUCT_BUNDLE_IDENTIFIER"] = "#{bundle_id}.tests"
  s["PRODUCT_NAME"] = "$(TARGET_NAME)"
  s["GENERATE_INFOPLIST_FILE"] = "YES"
  s["SWIFT_VERSION"] = app.build_configurations.first.build_settings["SWIFT_VERSION"] || "5.0"
  s["TARGETED_DEVICE_FAMILY"] = "1,2"
  s["CODE_SIGNING_ALLOWED"] = "NO"
end

group = project.main_group.new_group("AppTests", "AppTests")
target_dir = File.join(File.dirname(project_path), "AppTests")
Dir.mkdir(target_dir) unless Dir.exist?(target_dir)
Dir.glob(File.join(tests_dir, "*.swift")).sort.each do |src|
  name = File.basename(src)
  File.write(File.join(target_dir, name), File.read(src))
  tests.add_file_references([group.new_reference(name)])
end
abort "no Swift test in #{tests_dir}" if tests.source_build_phase.files.empty?
project.save

scheme = Xcodeproj::XCScheme.new
scheme.configure_with_targets(app, tests, launch_target: true)
scheme.test_action.code_coverage_enabled = false
scheme.save_as(project_path, "App", true)
puts "AppTests: #{tests.source_build_phase.files.size} file(s), host App, scheme App"
