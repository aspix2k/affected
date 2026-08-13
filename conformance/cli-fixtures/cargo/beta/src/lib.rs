pub fn value() -> u32 {
    affected_alpha::value() + 1
}

#[cfg(test)]
mod tests {
    #[test]
    fn returns_dependent_value() {
        assert_eq!(super::value(), 2);
    }
}
