pub fn value() -> u32 {
    4
}

#[cfg(test)]
mod tests {
    #[test]
    fn library_test_passes() {
        assert_eq!(super::value(), 4);
    }
}
