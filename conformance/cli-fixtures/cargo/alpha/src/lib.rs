/// ```
/// assert_eq!(affected_alpha::value(), 1);
/// ```
pub fn value() -> u32 {
    1
}

#[cfg(test)]
mod tests {
    #[test]
    fn returns_value() {
        assert_eq!(super::value(), 1);
    }
}
