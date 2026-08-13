require_relative "test_helper"

BETA_HELPER_STATE << :first_file_loaded

class BetaFirstTest < Minitest::Test
  def test_helper_and_first_file_are_loaded
    assert_includes BETA_HELPER_STATE, :first_file_loaded
  end
end
