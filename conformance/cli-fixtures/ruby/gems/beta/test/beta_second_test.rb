require_relative "test_helper"

class BetaSecondTest < Minitest::Test
  def test_helper_and_second_file_are_loaded
    assert_includes BETA_HELPER_STATE, :first_file_loaded
  end
end
