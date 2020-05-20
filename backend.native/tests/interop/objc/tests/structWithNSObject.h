#import <Foundation/NSString.h>

struct CStructWithNSObjects {
    id any;

    NSString* nsString;
    NSObject* object;

    NSArray* array;
    NSMutableArray* mutableArray;

    NSSet* set;

    NSDictionary* dictionary;
};

