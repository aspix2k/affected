require_relative "test_helper"

GAMMA_HELPER_STATE << :first_file_loaded

class GammaFirstTest < Test::Unit::TestCase
  def test_helper_and_first_file_are_loaded
    assert_include GAMMA_HELPER_STATE, :first_file_loaded
  end
end
