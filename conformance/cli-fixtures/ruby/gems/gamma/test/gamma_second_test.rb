require_relative "test_helper"

class GammaSecondTest < Test::Unit::TestCase
  def test_helper_and_second_file_are_loaded
    assert_include GAMMA_HELPER_STATE, :first_file_loaded
  end
end
