class Na {
    constructor(num) {
        this.num = num
    }

    double() {
        return this.num * 2
    }
}

class Kek {
    foo(a, b) {
        return a + b
    }

    fString(a, b) {
        return a + b
    }

    fDel(a, b) {
        return a / b
    }

    fObj(a, b) {
        return a.num + b.num
    }

    getDone(a) {
        a.done()
    }

    done() {
        return this.toString()
    }
}

// Start of exports generated by UTBot
export {Na,Kek}
// End of exports generated by UTBot
